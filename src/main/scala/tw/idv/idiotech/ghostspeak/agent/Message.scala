package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import shapeless._
import Poly._
import enumeratum._
import shapeless.Generic.Aux
import shapeless.PolyDefns.~>
import shapeless.ops.coproduct.Folder
import shapeless.ops.hlist.{ IsHCons, Mapper, RightFolder }

sealed trait Modality extends EnumEntry

object Modality extends Enum[Modality] {

  val values = findValues
  case object Sensed extends Modality
  case object Doing extends Modality
  case object Done extends Modality
  case object Intend extends Modality
}

sealed trait SpeechAct extends EnumEntry

object SpeechAct extends Enum[SpeechAct] {

  val values = findValues
  case object Inform extends SpeechAct
  case object Query extends SpeechAct
  case object Request extends SpeechAct
  case object Accept extends SpeechAct
}

// sender receiver content

// speech act: query, inform,

case class Message(
  sender: ActorRef[Message],
  speechAct: SpeechAct,
  modality: Modality,
  payload: String
)
