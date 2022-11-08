package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.Done
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.Perform
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  EventBase,
  FcmSender,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}
import io.circe.parser.decode
import io.circe.syntax._
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.{ Comparison, Node, Precondition }
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.circe.{ Decoder, Encoder }
import io.circe.generic.extras.ConfiguredJsonCodec
import tw.idv.idiotech.ghostspeak.daqiaotou.Task.VariableUpdates

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class ScenarioCreator(sensor: Sensor[EventPayload], actuator: Actuator[Content, EventPayload])
    extends LazyLogging {

  type Command = Sensor.Command[EventPayload]
  type NodeId = String
  type ActionId = String

  @ConfiguredJsonCodec
  case class Trigger(message: Message, nodes: List[Node])

  @ConfiguredJsonCodec
  case class StateStorage(triggers: List[Trigger], exclusions: Map[ActionId, List[NodeId]])

  case class State(
    triggers: Map[Message, List[Node]],
    exclusions: Map[ActionId, List[NodeId]],
    variables: Map[String, Int] = Map.empty
  ) extends EventBase {

    def apply(update: VariableUpdate): State = {
      val prevValue = variables.getOrElse(update.name, 0)
      val newValue: Int = update.operation match {
        case Operation.+   => prevValue + update.value
        case Operation.-   => prevValue - update.value
        case Operation.`=` => update.value
      }
      copy(variables = variables + (update.name -> newValue))
    }
    def apply(updates: List[VariableUpdate]): State = updates.foldLeft(this)((s, vu) => s.apply(vu))

    def check(precondition: Precondition): Boolean =
      variables
        .get(precondition.name)
        .fold(false)(v =>
          precondition.comparison match {
            case Comparison.>= => v >= precondition.value
            case Comparison.<= => v <= precondition.value
            case Comparison.== => v == precondition.value
          }
        )
    def check(preconds: List[Precondition]): Boolean = preconds.forall(check)
  }

  object State {

    implicit val decoder: Decoder[State] = implicitly[Decoder[StateStorage]].map(ss =>
      State(
        ss.triggers.map(t => t.message -> t.nodes).toMap,
        ss.exclusions,
        Map.empty
      )
    )

    implicit val encoder = implicitly[Encoder[StateStorage]].contramap[State](s =>
      StateStorage(s.triggers.toList.map(t => Trigger(t._1, t._2)), s.exclusions)
    )
  }

  @ConfiguredJsonCodec
  case class Event(nodes: List[Node], message: Message, variableUpdates: List[VariableUpdate])
      extends EventBase

  def onEvent(
    user: String
  )(state: State, event: Event)(implicit script: Map[String, Node]): State =
    if (event.nodes == List(Node.leave)) {
      val initial = script("initial").replace(user)
      State(initial.triggers.map(_ -> List(initial)).toMap, Map.empty, Map.empty)
    } else {
      val currentNodes = event.nodes
      val removalMap =
        currentNodes
          .flatMap(n => n.triggers.map(_ -> n))
          .groupBy(_._1)
          .map(p => p._1 -> p._2.map(_._2))
      val triggerMap: Map[Message, List[Node]] =
        if (currentNodes.isEmpty) Map.empty else currentNodes.map(_.childMap(user)).reduce(_ |+| _)
      val ret: Map[Message, List[Node]] =
        if (triggerMap.isEmpty) state.triggers
        else
          triggerMap.toList.foldLeft(state.triggers) { (s, trigger) =>
            val nodes = (s.getOrElse(trigger._1, Nil) ++ trigger._2).distinct
            if (nodes.nonEmpty) s + (trigger._1 -> nodes) else s - trigger._1
          }
      val removed =
        if (ret.isEmpty) ret
        else
          removalMap.toList.foldLeft(ret) { (s, trigger) =>
            val nodes = s.getOrElse(trigger._1, Nil).filterNot(trigger._2.toSet.contains).distinct
            if (nodes.nonEmpty) s + (trigger._1 -> nodes) else s - trigger._1
          }
      val excluded =
        if (currentNodes.isEmpty) removed
        else
          currentNodes
            .foldLeft(removed) { (s, node) =>
              if (node.exclusiveWith.nonEmpty) {
                s.view.mapValues(_.filterNot(n => node.exclusiveWith.contains(n.name))).toMap
              } else s
            }
            .filter(_._2.nonEmpty)
      val exclusions: Map[ActionId, List[NodeId]] = currentNodes.flatMap { n =>
        n.exclusiveWith
          .flatMap(name => script.get(name))
          .flatMap(x =>
            x.childMap(user).flatMap { p =>
              val xNodes: List[String] = p._2.map(_.name)
              n.performances.map(_.action.id -> xNodes)
            }
          )
      }.toMap ++ state.exclusions
      val result = event.message.actionId
        .filter(_ => event.message.payload.fold(_ == SystemPayload.Start, _ => false))
        .flatMap(state.exclusions.get)
        .fold(
          State(excluded, exclusions, state.variables)
        ) { nodeId =>
          State(
            excluded.view.mapValues(_.filterNot(n => nodeId.contains(n.name))).toMap,
            exclusions.filterNot(_._2 == nodeId),
            state.variables
          )
        }
      result.apply(event.variableUpdates)
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

  def onCommand(
    user: String,
    actuator: ActorRef[Actuator.Command[Content]]
  )(state: State, command: Command)(implicit script: Map[String, Node]): Effect[Event, State] =
    command match {
      case Sensor.Command.Sense(message, replyTo) =>
        def simplyPerform(action: Action) = {
          val a = action
            .copy(
              session = Session(message.scenarioId, None),
              receiver = user
            )
          logger.info(s"carrying out ${Perform(action, System.currentTimeMillis())}")
          actuator ! Perform(a, System.currentTimeMillis())
        }
        def getEffect(nodes: List[Node]): Effect[Event, State] = {
          logger.info(s"triggered nodes: ${nodes.map(_.name)}")
          val variableUpdates: List[VariableUpdate] =
            nodes.filter(n => state.check(n.preconditions)).flatMap { n =>
//              logger.info(s"effects: ${n.performances}")
              val vus: List[VariableUpdate] = n.performances.flatMap { p =>
                val action = p.action
                  .copy(
                    session = Session(message.scenarioId, None),
                    content = p.action.content.copy(exclusiveWith =
                      n.exclusiveWith.toList
                        .flatMap(e => script.get(e))
                        .flatMap(_.performances.map(_.action.id))
                    )
                  )
                val startTime = System.currentTimeMillis() + p.delay
                logger.info(s"carrying out ${Perform(action, startTime)}")
                action.content.task match {
                  case vu: VariableUpdates => vu.updates
                  case _ =>
                    actuator ! Perform(action, startTime)
                    Nil
                }
              }
              logger.info(s"transition to ${n.name}")
              vus
            }
          Effect.persist(Event(nodes, message, variableUpdates))
        }

        state.triggers.foreach { case (k, v) =>
          logger.info(s"trigger: responding to ${k.actionId.getOrElse("none")}")
          logger.info(s"======== triggering event: ${k.payload}")
          logger.info(
            s"======== triggered actions: ${v.map(_.performances.map(_.action.description))}"
          )
        }
        message.payload match {
          case Left(SystemPayload.Leave) =>
            logger.info(s"user $user left")
            val redisKey = s"action-${message.scenarioId}-$user"
            logger.info(s"deleting user from redis: $redisKey")
            redis.withClient(r => r.del(redisKey))
            Effect.persist(Event(List(Node.leave), message, Nil))
          case Right(EventPayload.PerformDirectly(action)) =>
            simplyPerform(action)
            Effect.none
          case Right(EventPayload.Text(reply)) =>
            val forComparison = message.forComparison.copy(payload = fakeTextPayload)
            def findMatch(matcher: (String, String) => Boolean): Option[List[Node]] = Option(
              state.triggers
                .filter {
                  case (k, _) =>
                    k.payload match {
                      case Right(EventPayload.Text(answer)) =>
                        k.copy(payload = fakeTextPayload) == forComparison && matcher(reply, answer)
                      case Left(_) => false
                    }
                  case _ => false
                }
                .values.flatten.toList
            ).filter(_.nonEmpty)

            val nodes: List[Node] = state.triggers
              .get(message.forComparison)
              .orElse(
                findMatch(textMatches)
              )
              .orElse(findMatch((_, a) => a == "fallback:"))
              .getOrElse(Nil)
              .map(_.replace(user))
            getEffect(nodes)
          case Right(EventPayload.GoldenFinger) =>
            getEffect(state.triggers.values.toList.flatten)
          case _ =>
            logger.info(s"message: $message")
            val node = state.triggers
              .get(message.forComparison)
              .map(_.map(_.replace(user)))
              .filter(_.nonEmpty)
              .getOrElse(Nil)
            if (node.nonEmpty) {
              logger.info(s"match: $node")
            }
            getEffect(node)
        }
      case Sensor.Command.Create(scenario, replyTo)    => Effect.none
      case Sensor.Command.Destroy(scenarioId, replyTo) => Effect.none
      case _                                           => Effect.none
    }

  def createScript(scenario: Scenario) = {
    logger.info(s"new template: ${scenario.template}")
    val ret = decode[List[Node]](scenario.template)
      .fold(throw _, identity)
      .map(n => n.name -> n)
      .toMap
    logger.info(s"new template to case class: $ret")
    ret
  }

  def ub(userScenario: Scenario, actuator: ActorRef[Actuator.Command[Content]])(implicit
    script: Map[String, Node]
  ) =
    Behaviors.setup[Command] { ctx =>
      val user = userScenario.id
      val initial: Node = script("initial").replace(user)
      logger.info(s"initial before = ${script("initial")}")
      logger.info(s"initial after = $initial")
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(s"scn-$user"),
        emptyState = State(initial.triggers.map(_ -> List(initial)).toMap, Map.empty, Map.empty),
        commandHandler = onCommand(user, actuator),
        eventHandler = onEvent(user)
      )
    }

  def createUserScenario(
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(actorContext: ActorContext[_], created: Sensor.Event.Created)(implicit
    script: Map[String, Node]
  ): Either[String, ActorRef[Command]] =
    if (created.scenario.id.isBlank) Left("empty user ID not allowed")
    else Right(actorContext.spawn(ub(created.scenario, actuatorRef), created.scenario.id))

  def sendMessage(
    action: Action
  )(implicit actorSystem: ActorSystem[_], encoder: Encoder[Action]): Future[Done] = {
    val actionJson = action.asJson.toString()
    val redisKey = s"action-${action.session.scenario}-${action.receiver}"
    val hashKey = action.id
    logger.info(s"saving action to redis: $redisKey $hashKey")
    redis.withClient(r => r.hset(redisKey, hashKey, actionJson))
    FcmSender.send(action)
  }

  val scenarioBehavior: Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      val sensorRef = ctx.self
      val actuatorBehavior = Behaviors.setup[Actuator.Command[Content]] { actx =>
        implicit val system = ctx.system

        def fcm(root: ActorRef[Actuator.Command[Content]]) =
          actuator.fromFuture(sendMessage, root)

        def discover(c: ActorContext[_], a: Action, r: ActorRef[Actuator.Command[Content]]) = Some {
          c.spawnAnonymous(fcm(actx.self))
        }
        actuator.behavior("graphscript", discover, sensorRef)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] =
        ctx.spawn(actuatorBehavior, UUID.randomUUID().toString)
      actuatorRef ! Actuator.Command.Timeout() // for recovery
      // TODO check engine before deciding actor
      sensor.apply(
        "graphscript",
        (ctx, created) =>
          if (created.scenario.engine == "graphscript")
            Try {
              val scn = created.scenario
              implicit val script: Map[String, Node] = createScript(scn)
              val actor: Behavior[Sensor.Command[EventPayload]] =
                sensor.apply(scn.id, createUserScenario(actuatorRef), sensor.onCommandPerUser)
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
