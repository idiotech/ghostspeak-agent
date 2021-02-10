package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import io.circe.Encoder
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.Actuator.Perform
import tw.idv.idiotech.ghostspeak.agent.{Action, Actuator, FcmSender, Sensor, SystemPayload}
import tw.idv.idiotech.ghostspeak.daqiaotou.Task.Popup

object ScenarioCreator {

  type Command = Sensor.Command[EventPayload]

  // should be event sourced
  // 1. spawn root sensor
  // 2. spawn actuator which references root sensor (with root as parameter)
  // 2. spawn child sensors from root
  // 3.

  implicit val actionEncoder: Encoder.AsObject[agent.Action[Content]] = Action.encoder[Content]




  def userBehavior(actuator: ActorRef[Actuator.Command[Content]]): Behavior[Command] = Behaviors.receiveMessage {
    case Sensor.Sense(message, replyTo) =>
      val maybeAction: Option[Action] = message.payload match {
        case Right(value) => value match {
          case Location(lat, lon) => None
          case EventPayload.Text(text) => if (text == "是的")
            Some(Action[Content]("", message, Task.Popup(Some("text"), Nil, false, None, Set(Destination.App))))
          else None
        }
        case Left(value) => value match {
          case SystemPayload.Ack => None
          case SystemPayload.Start => None
          case SystemPayload.End => None
          case SystemPayload.Join => Some(Action[Content]("", message, Task.Marker("", Location(20, 120), "http://www.google.com", OperationType.Add)))
          case SystemPayload.Leave => Some(Action[Content]("", message, Task.Popup(Some("bye"), Nil, false, None, Set(Destination.App))))
          case SystemPayload.Modal(modality, time) => None
        }
      }
      maybeAction.foreach(a => actuator ! Perform(a))
      Behaviors.same
    case Sensor.Create(scenarioId, template, replyTo) =>
      Behaviors.same
    case Sensor.Destroy(scenarioId, replyTo) =>
      Behaviors.same
  }

  def createUserScenario(actuatorRef: ActorRef[Actuator.Command[Content]])(actorContext: ActorContext[_], scenarioId: String, userId: String): Option[ActorRef[Command]] =
    Some(actorContext.spawn(userBehavior(actuatorRef), s"$scenarioId-$userId"))

  val scenarioBehavior: Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      val sensor = ctx.self
      val actuator = Behaviors.setup[Actuator.Command[Content]] { actx =>
        implicit val system = ctx.system
        def fcm(root: ActorRef[Actuator.Command[Content]]) =
          Actuator.fromFuture[Content](FcmSender.send[Content], root)

        def discover(c: ActorContext[_],
                     a: Action,
                     r: ActorRef[Actuator.Command[Content]]) = Some(
          c.spawnAnonymous(fcm(actx.self))
        )
        Actuator(discover, sensor)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] = ctx.spawn(actuator, "actuator")
      Sensor.perUser(createUserScenario(actuatorRef))
    }
  }

}
