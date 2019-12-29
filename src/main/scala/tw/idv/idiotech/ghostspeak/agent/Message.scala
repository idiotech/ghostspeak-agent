package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef

import enumeratum._

sealed trait Modality extends EnumEntry

object Modality extends Enum[Modality] {

  val values = findValues
  case object Sensed   extends Modality
  case object Doing    extends Modality
  case object Done     extends Modality
  case object Intend   extends Modality

}

sealed trait Event

sealed trait Action extends Event

case class Ghostspeak(sender: String, receiver: String) extends Action

// sender receiver content

case class Message(sender: ActorRef[Message], modality: String, payload: String)

