package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import tw.idv.idiotech.ghostspeak.agent.Actuator.Perform
import tw.idv.idiotech.ghostspeak.agent.Sensor.{ onCommandPerUser, Command, Created }
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  FcmSender,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}
import io.circe.parser.decode
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.Node
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import java.util.UUID
import scala.util.Try

object ScenarioCreator extends LazyLogging {

  type Command = Sensor.Command[EventPayload]
  type State = Map[Message, List[Node]]

  def onEvent(
    user: String
  )(state: State, events: List[Node])(implicit script: Map[String, Node]): State =
    if (events == List(Node.leave)) {
      val initial = script("initial").replace(user)
      initial.triggers.map(_ -> List(initial)).toMap
    } else {
      val removalMap =
        events.flatMap(n => n.triggers.map(_ -> n)).groupBy(_._1).map(p => p._1 -> p._2.map(_._2))
      val triggerMap: Map[Message, List[Node]] = events.map(_.childMap(user)).reduce(_ |+| _)
      val ret: State = triggerMap.toList.foldLeft(state) { (s, trigger) =>
        val nodes = (s.getOrElse(trigger._1, Nil) ++ trigger._2).distinct
        if (nodes.nonEmpty) s + (trigger._1 -> nodes) else s - trigger._1
      }
      val removed = removalMap.toList.foldLeft(ret) { (s, trigger) =>
        val nodes = s.getOrElse(trigger._1, Nil).filterNot(trigger._2.toSet.contains).distinct
        if (nodes.nonEmpty) s + (trigger._1 -> nodes) else s - trigger._1
      }
      events
        .foldLeft(removed) { (s, node) =>
          if (node.exclusiveWith.nonEmpty) {
            s.view.mapValues(_.filterNot(n => node.exclusiveWith.contains(n.name))).toMap
          } else s
        }
        .filter(_._2.nonEmpty)
    }

  def fakeTextPayload = Right(EventPayload.Text("fake_text"))

  val regexPattern = """regex:(.+)""".r
  val containsPattern = """contains:(.+)""".r

  def textMatches(reply: String, answer: String): Boolean =
    answer match {
      case regexPattern(text)    => text.r.matches(reply)
      case containsPattern(text) => reply.contains(text)
      case _                     => false
    }

  case class StringComparisonResult(matching: Option[Node] = None, fallback: Option[Node])

  def onCommand(
    user: String,
    actuator: ActorRef[Actuator.Command[Content]]
  )(state: State, command: Command): Effect[List[Node], State] = command match {
    case Sensor.Sense(message, replyTo) =>
      def getEffect(nodes: List[Node]): Effect[List[Node], State] = {
        val performances = nodes.flatMap(_.performances)
        performances.foreach { p =>
          val action = p.action.copy(session = Session(message.scenarioId, None))
          val startTime = System.currentTimeMillis() + p.delay
          logger.info(s"carrying out ${Perform(action, startTime)}")
          actuator ! Perform(action, startTime)
        }
        nodes.foreach(n => logger.info(s"transition to ${n.name}"))
        if (nodes.nonEmpty) Effect.persist(nodes) else Effect.none
      }

      message.payload match {
        case Left(SystemPayload.Leave) =>
          logger.info(s"user $user left")
          Effect.persist(List(Node.leave))
        case Right(EventPayload.Text(reply)) =>
          val forComparison = message.forComparison.copy(payload = fakeTextPayload)
          def findMatch(matcher: (String, String) => Boolean): Option[List[Node]] =
            state
              .find {
                case (k, _) =>
                  k.payload match {
                    case Right(EventPayload.Text(answer)) =>
                      k.copy(payload = fakeTextPayload) == forComparison && matcher(reply, answer)
                    case Left(_) => false
                  }
                case _ => false
              }
              .map(_._2)
              .filter(_.nonEmpty)
          val nodes: List[Node] = state
            .get(message.forComparison)
            .orElse(
              findMatch(textMatches)
            )
            .orElse(findMatch((_, a) => a == "fallback:"))
            .getOrElse(Nil)
            .map(_.replace(user))
          getEffect(nodes)
        case _ =>
          logger.info(s"trigger = ${state.keys}")
          logger.info(s"message = ${message.forComparison}")
          val node = state
            .get(message.forComparison)
            .map(_.map(_.replace(user)))
            .filter(_.nonEmpty)
            .getOrElse(Nil)
          getEffect(node)
      }
    case Sensor.Create(scenario, replyTo)    => Effect.none
    case Sensor.Destroy(scenarioId, replyTo) => Effect.none
  }

  def createScript(scenario: Scenario) =
    decode[List[Node]](scenario.template)
      .fold(throw _, identity)
      .map(n => n.name -> n)
      .toMap

  def ub(userScenario: Scenario, actuator: ActorRef[Actuator.Command[Content]])(implicit
    script: Map[String, Node]
  ) =
    Behaviors.setup[Command] { ctx =>
      val user = userScenario.id
      val initial: Node = script("initial").replace(user)
      EventSourcedBehavior[Command, List[Node], State](
        persistenceId = PersistenceId.ofUniqueId(s"scn-$user"),
        emptyState = initial.triggers.map(_ -> List(initial)).toMap,
        commandHandler = onCommand(user, actuator),
        eventHandler = onEvent(user)
      )
    }

  def createUserScenario(
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(actorContext: ActorContext[_], created: Created[EventPayload])(implicit
    script: Map[String, Node]
  ): Either[String, ActorRef[Command]] =
    Right(actorContext.spawn(ub(created.scenario, actuatorRef), created.scenario.id))

  val scenarioBehavior: Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      val sensor = ctx.self
      val actuator = Behaviors.setup[Actuator.Command[Content]] { actx =>
        implicit val system = ctx.system
        def fcm(root: ActorRef[Actuator.Command[Content]]) =
          Actuator.fromFuture[Content](FcmSender.send[Content], root)

        def discover(c: ActorContext[_], a: Action, r: ActorRef[Actuator.Command[Content]]) = Some {
          c.spawnAnonymous(fcm(actx.self))
        }
        Actuator("root", discover, sensor)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] =
        ctx.spawn(actuator, UUID.randomUUID().toString)
      actuatorRef ! Actuator.Timeout() // for recovery
      // TODO check engine before deciding actor
      Sensor[EventPayload](
        "root",
        (ctx, created) =>
          if (created.scenario.engine == "graphscript")
            Try {
              val scn = created.scenario
              implicit val script: Map[String, Node] = createScript(scn)
              val actor: Behavior[Sensor.Command[EventPayload]] =
                Sensor(scn.id, createUserScenario(actuatorRef), onCommandPerUser[EventPayload])
              ctx.spawn(
                actor,
                if (created.uniqueId.nonEmpty) created.uniqueId else UUID.randomUUID().toString
              )
            }
              .fold(
                e => {
                  e.printStackTrace()
                  Left(s"invalid template: ${e.getMessage}")
                },
                Right(_)
              )
          else Left("not a graphscript scenario")
      )
    }

}
