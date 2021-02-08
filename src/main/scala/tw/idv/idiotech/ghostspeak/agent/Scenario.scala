package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext

object Scenario {
  sealed trait Command[P]
  case object Destroy extends Command[Nothing]
  case class Sense[P](message: Message[P]) extends Command[P]

  type Creator[P] = (
    ActorContext[_],
    String,
    String
  ) => Option[ActorRef[Command[P]]]
}
