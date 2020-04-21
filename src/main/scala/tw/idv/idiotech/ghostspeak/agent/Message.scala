package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import enumeratum._

sealed trait Modality extends EnumEntry

object Modality extends Enum[Modality] {

  val values = findValues
  case object Sensed extends Modality
  case object Doing extends Modality
  case object Done extends Modality
  case object Intend extends Modality
}

sealed trait Performative extends EnumEntry

// sender receiver content

// speech act: query, inform,

trait Payload

sealed trait Message

trait Item extends Product with Serializable

case class Belief(modality: Modality, item: Item) extends Payload

case class InformMessage(
  id: Int,
  sender: ActorRef[AckMessage],
  beliefs: List[Belief]
)

case class AckMessage(
  id: Int,
  beliefs: Belief
)
case class QueryMessage(
  id: Int,
  sender: ActorRef[InformMessage],
  query: Query
)
