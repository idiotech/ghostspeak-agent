package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import io.circe.generic.JsonCodec

object DummySensor {

  // Trait defining successful and failure responses
  @JsonCodec
  sealed trait Response extends Command
  case object OK extends Response
  final case class KO(reason: String) extends Response

  // Trait and its implementations representing all possible messages that can be sent to this Behavior
  sealed trait Command
  final case class Sense(event: Message, replyTo: ActorRef[Response]) extends Command

  // This behavior handles all possible incoming messages and keeps the state in the function parameter
  def apply(planner: ActorRef[Command], events: List[Message] = Nil): Behavior[Command] =
    Behaviors.receive {
      case (ctx, Sense(event, replyTo)) =>
        DummySensor(planner, event :: events)
        planner ! Sense(event, ctx.self)
        replyTo ! OK
        Behaviors.same
      case (_, OK) =>
        Behaviors.same
      case (_, KO(_)) =>
        Behaviors.same
    }

}
