package tw.idv.idiotech.ghostspeak

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.{ Decoder, Encoder, Json }
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }
import io.circe.generic.extras.semiauto._

import scala.collection.immutable
import json.schema._
package object agent {

  implicit val configuration = Configuration.default
    .withDiscriminator("type")
    .withScreamingSnakeCaseConstructorNames
    .withDefaults

  trait CirceSerializable

  @ConfiguredJsonCodec
  case class Session(scenario: String, chapter: Option[String]) extends CirceSerializable

  @typeHint[String]
  sealed trait Modality extends EnumEntry

  object Modality extends Enum[Modality] with CirceEnum[Modality] {
    val values: immutable.IndexedSeq[Modality] = findValues
    case object Doing extends Modality with UpperSnakecase
    case object Done extends Modality with UpperSnakecase
    case object Failed extends Modality with UpperSnakecase
    case object Intended extends Modality with UpperSnakecase
  }

  @title("Action for clients to execute")
  @description(
    "An action represents something to execute on the devices. It is sent from the server to the client."
  )
  case class Action[Content](
    @description("Unique ID for an action instance")
    id: String,
    @description("Unique ID of the receiver; usually the user ID.")
    receiver: String,
    @description("Unique ID of the sender; usually a constant string or the name of an NPC.")
    sender: String,
    @description("Content to be shown or played. Can be text/image popup, sound or marker.")
    content: Content,
    @description("The session ID indicates a chapter, episode, etc.")
    session: Session,
    @description("Human-readable info for the action")
    description: Option[String] = None
  ) {

    def toMessage[T](modality: Modality) =
      Message(
        "id",
        Some(id),
        sender,
        receiver,
        SystemPayload.Modal(modality).asPayload[T],
        session.scenario
      )
  }

  object Action {
    def decoder[T](implicit decoder: Decoder[T]) = deriveConfiguredDecoder[Action[T]]
    def encoder[T](implicit encoder: Encoder[T]) = deriveConfiguredEncoder[Action[T]]

    def apply[C](id: String, message: Message[_], content: C): Action[C] =
      Action[C](id, message.sender, message.receiver, content, Session(message.scenarioId, None))
  }

  type Payload[T] = Either[SystemPayload, T]

  object Payload {

    def h[T](implicit b: Decoder[T]): Decoder[Either[SystemPayload, T]] = {
      val l: Decoder[Either[SystemPayload, T]] = implicitly[Decoder[SystemPayload]].map(Left.apply)
      val r: Decoder[Either[SystemPayload, T]] = b.map(Right.apply)
      l or r
    }

    def e[T](implicit b: Encoder[T]): Encoder[Payload[T]] = new Encoder[Payload[T]] {

      override def apply(a: Payload[T]): Json =
        a.fold(l => implicitly[Encoder[SystemPayload]].apply(l), r => b(r))
    }
  }

  @ConfiguredJsonCodec
  sealed trait SystemPayload {
    def asPayload[T] = Left[SystemPayload, T](this)
  }

  object SystemPayload {

    @typeHint[String]
    case object Ack extends SystemPayload

    @typeHint[String]
    case object Start extends SystemPayload

    @typeHint[String]
    case object End extends SystemPayload

    @typeHint[String]
    case object Join extends SystemPayload

    @typeHint[String]
    case object Leave extends SystemPayload

    case class Modal(modality: Modality, time: Long = System.currentTimeMillis())
        extends SystemPayload {
      override def hashCode(): Int = modality.hashCode()

      override def equals(obj: Any): Boolean = obj match {
        case other: Modal => other.modality == modality
        case _            => false
      }
    }
  }

  @title("Events for server to process")
  @description(
    "An event represents something to process on the server. It is sent from the client to the server."
  )
//  @ConfiguredJsonCodec
  case class Message[T](
    @description("Unique ID for an event instance")
    id: String,
    @description("Action ID for which the event is in reply to")
    actionId: Option[String],
    @description("Unique ID of the receiver; usually a constant string or the name of an NPC.")
    receiver: String,
    @description("Unique ID of the sender; usually the user ID.")
    sender: String,
    @description("what the client wants to tell the server")
    payload: Payload[T],
    @description("The scenario this event belongs to")
    scenarioId: String
  ) {
    def forComparison = copy(id = "", scenarioId = "")
  }

  object Message {

    def decoder[T](implicit decoder: Decoder[T]) = {
      implicit val payloadDec = Payload.h[T]
      deriveConfiguredDecoder[Message[T]]
    }

    def encoder[T](implicit encoder: Encoder[T]) = {
      implicit val payloadEnc = Payload.e[T]
      deriveConfiguredEncoder[Message[T]]
    }
  }

  @ConfiguredJsonCodec
  case class Metadata(
    name: Option[String] = None,
    displayName: Option[String] = None,
    description: Option[String] = None,
    featured: Boolean = false,
    public: Boolean = false,
    owner: Option[String] = None,
    ordinal: Long = 9999999999999L,
    categories: List[String] = Nil,
    details: Option[String] = None,
    image: Option[String] = None
  )

  @ConfiguredJsonCodec
  case class Scenario(
    id: String,
    engine: String,
    template: String,
    name: Option[String] = None,
    displayName: Option[String] = None,
    public: Boolean = false,
    owner: Option[String] = None,
    ordinal: Long = 9999999999999L,
    metadata: Metadata = Metadata()
  )

}
