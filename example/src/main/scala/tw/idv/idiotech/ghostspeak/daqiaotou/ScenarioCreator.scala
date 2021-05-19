package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import io.circe.Encoder
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.Actuator.Perform
import tw.idv.idiotech.ghostspeak.agent.Sensor.{ onCommandPerUser, Command }
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  FcmSender,
  Scenario,
  Sensor,
  Session,
  SystemPayload,
  Action => BaseAction
}
import com.github.kolotaev.ride.Id
import io.circe.parser.decode
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.Node2

import java.util.UUID
import scala.io.Source

object ScenarioCreator {

  type Command = Sensor.Command[EventPayload]
  type State = Map[Message, Node2]

  def onEvent(
    user: String
  )(state: State, event: Node2)(implicit script: Map[String, Node2]): State = {
    val ret = (state -- event.triggers) ++ event.childMap(user)
    if (event.exclusiveWith.nonEmpty) ret.filter(p => !event.exclusiveWith.contains(p._2.name))
    else ret
  }

  def onCommand(
    user: String,
    actuator: ActorRef[Actuator.Command[Content]]
  )(state: State, command: Command): Effect[Node2, State] = command match {
    case Sensor.Sense(message, replyTo) =>
      println(s"trigger = ${state.keys.headOption}")
      println(s"message = ${message.forComparison}")
      val node = state.get(message.forComparison).map(_.replace(user))
      val actions = node.map(_.actions).getOrElse(Nil)
      actions.foreach(a => actuator ! Perform(a.copy(session = Session(message.scenarioId, None))))
      node.fold[Effect[Node2, State]](Effect.none)(n => Effect.persist(n))
    case Sensor.Create(scenario, replyTo)    => Effect.none
    case Sensor.Destroy(scenarioId, replyTo) => Effect.none
  }

  def ub(
    scenario: Scenario
  )(userScenario: Scenario, actuator: ActorRef[Actuator.Command[Content]]) =
    Behaviors.setup[Command] { ctx =>
      implicit val script: Map[String, Node2] =
        decode[List[Node2]](scenario.template)
          .fold(throw _, identity)
          .map(n => n.name -> n)
          .toMap
      val user = userScenario.id
      val initial: Node2 = script("initial").replace(user)
      EventSourcedBehavior[Command, Node2, State](
        persistenceId = PersistenceId.ofUniqueId(s"scn-$user"),
        emptyState = initial.triggers.map(_ -> initial).toMap,
        commandHandler = onCommand(user, actuator),
        eventHandler = onEvent(user)
      )
    }

  /*
  type State = Map[Message, List[Action]]
  sealed trait Event
  case class CreateTrigger(message: Message, actions: List[Action]) extends Event
  case class RemoveTrigger(message: Message, actions: List[Action]) extends Event

  def action1(user: String) = BaseAction[Content](
    "action 1",
    user,
    "ghost",
    Content(
      Task.Popup(Some("你有聽見我說話嗎？"), List("請說", "(離開吧，對你比較好)"), false, None, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )
  def action1_1(user: String) = BaseAction[Content](
    "action 1",
    user,
    "ghost",
    Content(
      Task.Popup(Some("沒有人聽見我說話，到底什麼時候信才能送到她手上呢？"), Nil, false, None, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )
  def action2(user: String) = BaseAction[Content](
    "action 2",
    user,
    "ghost",
    Content(
      Task.Popup(Some("幫我把這封信交給她吧，時間不多，返航的船笛已經在響了。"), Nil, false, Some("http://daqiaotou-storage.floraland.tw/images/letter.png"), Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )
  def action3(user: String) = BaseAction[Content](
    "action 3",
    user,
    "ghost",
    Content(
      Task.Popup(Some("之後一定會再跟你聯絡的。"), Nil, false, None, Set(Destination.App)),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )
  def action4(user: String) = BaseAction[Content](
    "action 4",
    user,
    "ghost",
    Content(
      Task.Marker("marker1", Location(24.0, 120.0), "", OperationType.Add),
      Condition.Always
    ),
    Session("scenario1", Some(""))
  )
  def action5(user: String) = BaseAction[Content](
    "action 5",
    user,
    "ghost",
    Content(
      Task.Marker("marker1", Location(24.0, 120.0), "", OperationType.Delete),
      Condition.Geofence(Location(24.0, 120.0), 100)
    ),
    Session("scenario1", Some(""))
  )
  def action6(user: String) = BaseAction[Content](
    "action 6",
    user,
    "ghost",
    Content(
      Task.Sound("http://daqiaotou-storage.floraland.tw/sounds/reply.mp3", None, SoundType.Main),
      Condition.Geofence(Location(24.0, 120.0), 100)
    ),
    Session("scenario1", Some(""))
  )
  //
  def message1(user: String) = agent.Message[EventPayload]("", actionId = None, receiver = "ghost", sender = user, payload = Left(SystemPayload.Join), scenarioId = "scenario1")
  def message2(user: String) = agent.Message[EventPayload]("", actionId = Some("action 1"), receiver = "ghost", sender = user, payload = Right(EventPayload.Text("請說")), scenarioId = "scenario1")
  def message2_1(user: String) = agent.Message[EventPayload]("", actionId = Some("action 1"), receiver = "ghost", sender = user, payload = Right(EventPayload.Text("(離開吧，對你比較好)")), scenarioId = "scenario1")
  def message3(user: String) = agent.Message[EventPayload]("", actionId = Some("action 2"), receiver = "ghost", sender = user, payload = Left(SystemPayload.End), scenarioId = "scenario1")

  def initialState(user: String) = Map(
    message1(user) -> List(action1(user)),
    message2_1(user) -> List(action1_1(user)),
    message2(user) -> List(action2(user), action4(user), action5(user), action6(user)),
    message3(user) -> List(action3(user)),
  )

  def onCommand(user: String, actuator: ActorRef[Actuator.Command[Content]])(state: State, command: Command): Effect[Event, State] = command match {
    case Sensor.Sense(message, replyTo) =>
      val actions: List[Action] = state.get(message.forComparison).getOrElse(Nil)
      actions.foreach(a => actuator ! Perform(a))
      if (actions.nonEmpty) Effect.persist(RemoveTrigger(message.forComparison, actions)) else Effect.none
    case Sensor.Create(scenario, replyTo) => Effect.none
    case Sensor.Destroy(scenarioId, replyTo) => Effect.none
  }

  def onEvent(state: State, event: Event): State = event match {
    case CreateTrigger(message, actions) =>
      state + (message -> (state.getOrElse(message, Nil) ++ actions))
    case RemoveTrigger(message, actions) =>
      val old = state.getOrElse(message, Nil).filterNot(actions.contains)
      val newState = if (old.isEmpty) state - message else state + (message -> old)
      newState
  }

  def ub(user: String, actuator: ActorRef[Actuator.Command[Content]]) =
    Behaviors.setup[Command] { ctx =>
      EventSourcedBehavior[Command, Event, State](
          persistenceId = PersistenceId.ofUniqueId(s"scn-$user"),
          emptyState = initialState(user),
          commandHandler = onCommand(user, actuator),
          eventHandler = onEvent
        )
    }
   */
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
        Actuator(discover, sensor)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] =
        ctx.spawn(actuator, UUID.randomUUID().toString)
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
