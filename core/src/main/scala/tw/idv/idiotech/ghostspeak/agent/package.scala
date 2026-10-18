package tw.idv.idiotech.ghostspeak

import io.circe.Codec

import com.typesafe.config.ConfigFactory
import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.{ Decoder, Encoder, Json }
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.config.syntax._

import scala.collection.immutable
import org.apache.pekko.http.scaladsl.model.headers.HttpOrigin
package object agent {

  implicit val configuration: Configuration = Configuration.default
    .withDiscriminator("type")
    .withScreamingSnakeCaseConstructorNames
    .withDefaults

  trait CirceSerializable

  case class Session(scenario: String, chapter: Option[String]) extends CirceSerializable

  object Session {
    implicit val codecForSession: Codec.AsObject[Session] = deriveConfiguredCodec[Session]
  }

  sealed trait Modality extends EnumEntry

  object Modality extends Enum[Modality] with CirceEnum[Modality] {
    val values: immutable.IndexedSeq[Modality] = findValues
    case object Doing extends Modality with UpperSnakecase
    case object Done extends Modality with UpperSnakecase
    case object Failed extends Modality with UpperSnakecase
    case object Intended extends Modality with UpperSnakecase
  }

  // Action for clients to execute
  // An action represents something to execute on the devices. It is sent from the server to the client.
  case class Action[Content](
    // Unique ID for an action instance
    id: String,
    // Unique ID of the receiver; usually the user ID.
    receiver: String,
    // Unique ID of the sender; usually a constant string or the name of an NPC.
    sender: String,
    // Content to be shown or played. Can be text/image popup, sound or marker.
    content: Content,
    // The session ID indicates a chapter, episode, etc.
    session: Session,
    // Human-readable info for the action
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

  sealed trait SystemPayload {
    def asPayload[T] = Left[SystemPayload, T](this)
  }

  object SystemPayload {

    case object Ack extends SystemPayload

    case object Start extends SystemPayload

    case object End extends SystemPayload

    case object Join extends SystemPayload

    case object Leave extends SystemPayload

    case class Modal(modality: Modality, time: Long = System.currentTimeMillis())
        extends SystemPayload {
      override def hashCode(): Int = modality.hashCode()

      override def equals(obj: Any): Boolean = obj match {
        case other: Modal => other.modality == modality
        case _            => false
      }
    }

    implicit val codecForSystemPayload: Codec.AsObject[SystemPayload] =
      deriveConfiguredCodec[SystemPayload]
  }

  // Events for server to process
  // An event represents something to process on the server. It is sent from the client to the server.
  case class Message[T](
    // Unique ID for an event instance
    id: String,
    // Action ID for which the event is in reply to
    actionId: Option[String],
    // Unique ID of the receiver; usually a constant string or the name of an NPC.
    receiver: String,
    // Unique ID of the sender; usually the user ID.
    sender: String,
    // what the client wants to tell the server
    payload: Payload[T],
    // The scenario this event belongs to
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

  case class Metadata(
    name: Option[String] = None,
    displayName: Option[String] = None,
    description: Option[String] = None,
    featured: Boolean = false,
    public: Boolean = false,
    passcode: Option[String] = None,
    owner: Option[String] = None,
    ordinal: Long = 9999999999999L,
    categories: List[String] = Nil,
    details: Option[String] = None,
    image: Option[String] = None
  )

  object Metadata {
    implicit val codecForMetadata: Codec.AsObject[Metadata] = deriveConfiguredCodec[Metadata]
  }

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

  object Scenario {
    implicit val codecForScenario: Codec.AsObject[Scenario] = deriveConfiguredCodec[Scenario]
  }

  case class AgentConf(corsDomains: String) {
    println(s"++++++++ ${corsDomains.split(",").toList}")
    val corsDomainList: Array[HttpOrigin] = corsDomains.split(",").map(HttpOrigin.apply)
  }

  object AgentConf {
    implicit val codecForAgentConf: Codec.AsObject[AgentConf] = deriveConfiguredCodec[AgentConf]
  }

  lazy val agentConfig: AgentConf = {
    ConfigFactory.load().as[AgentConf]("app").fold(throw _, identity)
  }
}
