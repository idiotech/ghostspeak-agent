package tw.idv.idiotech.ghostspeak

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }

import scala.collection.immutable
import json.schema._
package object agent {

  private implicit val configuration = Configuration.default
    .withDiscriminator("type")
    .copy(transformConstructorNames = _.toUpperCase())


  @ConfiguredJsonCodec
  case class Session(scenario: String, chapter: String)

  @typeHint[String]
  sealed trait Modality extends EnumEntry

  object Modality extends Enum[Modality] with CirceEnum[Modality] {
    val values: immutable.IndexedSeq[Modality] = findValues
    case object Doing extends Modality with UpperSnakecase
    case object Done extends Modality with UpperSnakecase
    case object Failed extends Modality with UpperSnakecase
    case object Intended extends Modality with UpperSnakecase
  }

  @ConfiguredJsonCodec
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
    @description(
      "The action is triggered only when the the condition is matched; if condition is null, the action is triggered immediately."
    )
//    condition: Option[Condition],
//    @description("Content to be shown or played. Can be text/image popup, sound or marker.")
    content: Content,
    @description("The session ID indicates a chapter, episode, etc.")
    session: Session
  ) {
    def toMessage(modality: Modality) =
      Message("id", Some(id), receiver, sender, Payload.Modal(modality), session.scenario)
  }

  @ConfiguredJsonCodec
  sealed trait Payload

  object Payload {

    @typeHint[String]
    case object Ack extends Payload

    @typeHint[String]
    case object Start extends Payload

    @typeHint[String]
    case object End extends Payload
    case class Text(text: String) extends Payload

    @typeHint[String]
    case object Join extends Payload

    @typeHint[String]
    case object Leave extends Payload

    case class Modal(modality: Modality, time: Long = System.currentTimeMillis()) extends Payload
  }

  @ConfiguredJsonCodec
  @title("Events for server to process")
  @description(
    "An event represents something to process on the server. It is sent from the client to the server."
  )
  case class Message(
    @description("Unique ID for an event instance")
    id: String,
    @description("Action ID for which the event is in reply to")
    actionId: Option[String],
    @description("Unique ID of the receiver; usually a constant string or the name of an NPC.")
    receiver: String,
    @description("Unique ID of the sender; usually the user ID.")
    sender: String,
    @description("what the client wants to tell the server")
    payload: Payload,
    @description("The scenario this event belongs to")
    scenarioId: String
  )

}
