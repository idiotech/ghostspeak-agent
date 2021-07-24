package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import tw.idv.idiotech.ghostspeak.agent.Actuator.Perform
import tw.idv.idiotech.ghostspeak.agent.Sensor.{ onCommandPerUser, Command }
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  FcmSender,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}
import io.circe.parser.{ decode, parse }
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.Node

import java.util.UUID

object ScenarioCreator {

  type Command = Sensor.Command[EventPayload]
  type State = Map[Message, Node]

  def onEvent(
    user: String
  )(state: State, event: Node)(implicit script: Map[String, Node]): State =
    if (event == Node.leave) {
      val initial = script("initial").replace(user)
      initial.triggers.map(_ -> initial).toMap
    } else {
      val ret = (state -- event.triggers) ++ event.childMap(user)
      if (event.exclusiveWith.nonEmpty) ret.filter(p => !event.exclusiveWith.contains(p._2.name))
      else ret
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
  )(state: State, command: Command): Effect[Node, State] = command match {
    case Sensor.Sense(message, replyTo) =>
      def getEffect(node: Option[Node]): Effect[Node, State] = {
        val performances = node.map(_.performances).getOrElse(Nil)
        performances.foreach { p =>
          val action = p.action.copy(session = Session(message.scenarioId, None))
          val startTime = System.currentTimeMillis() + p.delay
          actuator ! Perform(action, startTime)
        }
        node.fold[Effect[Node, State]](Effect.none)(n => Effect.persist(n))
      }

      message.payload match {
        case Left(SystemPayload.Leave) =>
          println(s"user $user left")
          Effect.persist(Node.leave)
        case Right(EventPayload.Text(reply)) =>
          val forComparison = message.forComparison.copy(payload = fakeTextPayload)
          def findMatch(matcher: (String, String) => Boolean): Option[Node] =
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
          val node: Option[Node] = state
            .get(message.forComparison)
            .orElse(
              findMatch(textMatches)
            )
            .orElse(findMatch((_, a) => a == "fallback:"))
            .map(_.replace(user))
          getEffect(node)
        case _ =>
          println(s"trigger = ${state.keys.headOption}")
          println(s"message = ${message.forComparison}")
          val node = state.get(message.forComparison).map(_.replace(user))
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

  def ub(
    scenario: Scenario
  )(userScenario: Scenario, actuator: ActorRef[Actuator.Command[Content]]) =
    Behaviors.setup[Command] { ctx =>
      implicit val script: Map[String, Node] = createScript(scenario)
      val user = userScenario.id
      val initial: Node = script("initial").replace(user)
      EventSourcedBehavior[Command, Node, State](
        persistenceId = PersistenceId.ofUniqueId(s"scn-$user"),
        emptyState = initial.triggers.map(_ -> initial).toMap,
        commandHandler = onCommand(user, actuator),
        eventHandler = onEvent(user)
      )
    }

  def createUserScenario(
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(
    scenario: Scenario
  )(actorContext: ActorContext[_], userScenario: Scenario): Option[ActorRef[Command]] =
    Some(actorContext.spawn(ub(scenario)(userScenario, actuatorRef), userScenario.id))

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
        (ctx, scn) =>
          if (scn.engine == "graphscript") {
            val actor: Behavior[Sensor.Command[EventPayload]] =
              Sensor(scn.id, createUserScenario(actuatorRef)(scn), onCommandPerUser[EventPayload])
            Some(ctx.spawn(actor, UUID.randomUUID().toString))
          } else None
      )
    }

}
