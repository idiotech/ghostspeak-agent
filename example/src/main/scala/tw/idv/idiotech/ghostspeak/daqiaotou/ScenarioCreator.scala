package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import io.circe.Encoder
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.Actuator.Perform
import tw.idv.idiotech.ghostspeak.agent.{
  Actuator,
  FcmSender,
  Scenario,
  Sensor,
  SystemPayload,
  Action => BaseAction
}

object ScenarioCreator {

  type Command = Sensor.Command[EventPayload]

  implicit val actionEncoder: Encoder.AsObject[agent.Action[Content]] = BaseAction.encoder[Content]

  def userBehavior[EventPayload](actuator: ActorRef[Actuator.Command[Content]]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Sensor.Sense(message, replyTo) =>
        val maybeAction: Option[Action] = message.payload match {
          case Right(value) =>
            value match {
              case Location(lat, lon) => None
              case EventPayload.Text(text) =>
                if (text == "是的")
                  Some(
                    BaseAction[Content](
                      "",
                      message,
                      Content(
                        Task.Popup(Some("text"), Nil, false, None, Set(Destination.App)),
                        Condition.Always
                      )
                    )
                  )
                else None
            }
          case Left(value) =>
            value match {
              case SystemPayload.Ack   => None
              case SystemPayload.Start => None
              case SystemPayload.End   => None
              case SystemPayload.Join =>
                Some(
                  BaseAction[Content](
                    "",
                    message,
                    Content(
                      Task
                        .Marker("", Location(20, 120), "http://www.google.com", OperationType.Add),
                      Condition.Always
                    )
                  )
                )
              case SystemPayload.Leave =>
                Some(
                  BaseAction[Content](
                    "",
                    message,
                    Content(
                      Task.Popup(Some("bye"), Nil, false, None, Set(Destination.App)),
                      Condition.Always
                    )
                  )
                )
              case SystemPayload.Modal(modality, time) => None
            }
        }
        println(s"====maybe action: $maybeAction")
        maybeAction.foreach(a => actuator ! Perform(a))
        Behaviors.same
      case Sensor.Create(scenario, replyTo) =>
        Behaviors.same
      case Sensor.Destroy(scenarioId, replyTo) =>
        Behaviors.same
    }

  def createUserScenario(
    actuatorRef: ActorRef[Actuator.Command[Content]]
  )(actorContext: ActorContext[_], scenario: Scenario): Option[ActorRef[Command]] =
    Some(actorContext.spawn(userBehavior(actuatorRef), scenario.id))

  val scenarioBehavior: Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      val sensor = ctx.self
      val actuator = Behaviors.setup[Actuator.Command[Content]] { actx =>
        implicit val system = ctx.system
        def fcm(root: ActorRef[Actuator.Command[Content]]) =
          Actuator.fromFuture[Content](FcmSender.send[Content], root)

        def discover(c: ActorContext[_], a: Action, r: ActorRef[Actuator.Command[Content]]) = Some {
          println("===== creating actuator")
          c.spawnAnonymous(fcm(actx.self))
        }
        Actuator(discover, sensor)
      }
      val actuatorRef: ActorRef[Actuator.Command[Content]] = ctx.spawn(actuator, "actuator")
      Sensor.perUser("root", createUserScenario(actuatorRef))
    }
  }

}
