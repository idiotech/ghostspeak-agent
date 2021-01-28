package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.slf4j.Marker
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.agent.Content.{Marker, Popup}
import tw.idv.idiotech.ghostspeak.agent.DummySensor.{OK, Sense}

import java.util.UUID

object DummyPlanner {

  case class Executor(actionId: String, actorRef: ActorRef[FcmSender.Command]) {
    def perform(userId: String, condition: Option[Condition], content: Content, session: Option[String]) = {
      actorRef ! FcmSender.Perform(
        Action(
          actionId, userId, "ghost", condition, content, session
        )
      )
    }
  }
  def getExecutor(ctx: ActorContext[Sense], id: String = UUID.randomUUID().toString) = {
    Executor(id, ctx.spawn(FcmSender(), id))
  }

  def apply(userId: String, state: Int): Behavior[DummySensor.Sense] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage {
      case Sense(event, replyTo) =>
        event.payload match {
          case Location(lat, lon) =>
          case Payload.Ack =>
            if (event.actionId.contains("yes"))
              getExecutor(ctx).perform(userId, None,
                Content.Marker("marker 1", Location(24, 124), "", OperationType.Add),
                Some("Intro"))
          case Payload.Start =>
          case Payload.End =>
            getExecutor(ctx).perform(userId, None,
              Popup(Some("Do you love me?"), List("yes", "no"), false, None, Set(Destination.App)),
              Some("Intro"))
          case Payload.Text("hi") =>
            getExecutor(ctx).perform(userId, None,
              Popup(Some("Do you love me?"), List("yes", "no"), false, None, Set(Destination.App)),
              Some("Intro"))
          case Payload.Text("yes") =>
            getExecutor(ctx, "action_no").perform(userId, None,
              Popup(Some("I do, too"), Nil, false, None, Set(Destination.App)),
              Some("Intro"))
          case Payload.Text("no") =>
            getExecutor(ctx, "action_yes").perform(userId, None,
              Popup(Some("Then goodbye......forever"), Nil, false, None, Set(Destination.App)),
              Some("Intro"))
          case Payload.Text("") =>
            getExecutor(ctx).perform(userId, None,
              Popup(Some("Then goodbye......forever"), Nil, false, None, Set(Destination.App)),
              Some("Intro"))
          case _ =>
        }
        Behaviors.same
    }
  }

}
