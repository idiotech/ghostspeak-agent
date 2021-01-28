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

  @typeHint[String]
  sealed trait Destination extends EnumEntry

  object Destination extends Enum[Destination] with CirceEnum[Destination] {
    val values: immutable.IndexedSeq[Destination] = findValues
    case object App extends Destination with UpperSnakecase
    case object Notification extends Destination with UpperSnakecase
  }

  @typeHint[String]
  sealed trait SoundType extends EnumEntry

  object SoundType extends Enum[SoundType] with CirceEnum[SoundType] {
    val values: immutable.IndexedSeq[SoundType] = findValues
    case object Main extends SoundType with UpperSnakecase
    case object Background extends SoundType with UpperSnakecase
    case object Loop extends SoundType with UpperSnakecase
  }

  @typeHint[String]
  sealed trait OperationType extends EnumEntry

  object OperationType extends Enum[OperationType] with CirceEnum[OperationType] {
    val values: immutable.IndexedSeq[OperationType] = findValues
    case object Add extends OperationType with UpperSnakecase
    case object Delete extends OperationType with UpperSnakecase
  }

  @typeHint[String]
  sealed trait BeaconType extends EnumEntry

  object BeaconType extends Enum[BeaconType] with CirceEnum[BeaconType] {
    val values: immutable.IndexedSeq[BeaconType] = findValues
    case object Enter extends BeaconType with UpperSnakecase
    case object Exit extends BeaconType with UpperSnakecase
  }

  @ConfiguredJsonCodec
  case class Location(lat: Double, lon: Double) extends Payload

  @ConfiguredJsonCodec
  sealed trait Content

  object Content {

    @title("Popup message")
    @description("Client should show it as a notification or a popup window in the app.")
    case class Popup(
      @description("This is the message to be shown. Can be null.")
      text: Option[String],
      @description(
        "They are acceptable choices from the user. They can be shown as buttons or drop-down menu depending on client design."
      )
      choices: List[String],
      @description("If true, user is allowed to enter a text reply")
      allowTextReply: Boolean,
      @title("Picture URL to be shown")
      picture: Option[String],
      @title("Destinations to be shown")
      destinations: Set[Destination]
    ) extends Content

    @title("Sound message")
    @description("Client should play the sound.")
    case class Sound(
      @description("URL of the sound file")
      url: String,
      @description("The length of the part of the sound file that contains speech")
      speechLength: Option[Int],
      @description("Controls whether the sound should be queued or looped")
      `type`: SoundType
    ) extends Content

    @title("Marker message")
    @description("Client should display the marker on map.")
    case class Marker(
      @description("Unique ID for a marker instance.")
      id: String,
      @description("The marker should be shown at this location.")
      location: Location,
      @description("The icon image URL for the marker.")
      icon: String,
      @description("This field tells the client whether to add or delete this marker.")
      operation: OperationType
    ) extends Content
  }

  @ConfiguredJsonCodec
  sealed trait Condition

  object Condition {

    @title("Geofence")
    @description("Location condition for action.")
    case class Geofence(
      @description("Geographic coordinates")
      location: Location,
      @description("The radius of the geofence in meters.")
      radius: Int
    ) extends Condition

    @title("Beacon")
    @description("Beacon condition for action")
    case class Beacon(
      @description("Beacon ID")
      id: String,
      @description("Threshold value for triggering")
      threshold: Int,
      @description("Whether to activate on enter or on exit")
      `type`: BeaconType
    ) extends Condition
  }

  @ConfiguredJsonCodec
  case class Session(scenario: String, chapter: String)

  @ConfiguredJsonCodec
  @title("Action for clients to execute")
  @description(
    "An action represents something to execute on the devices. It is sent from the server to the client."
  )
  case class Action(
    @description("Unique ID for an action instance")
    id: String,
    @description("Unique ID of the receiver; usually the user ID.")
    receiver: String,
    @description("Unique ID of the sender; usually a constant string or the name of an NPC.")
    sender: String,
    @description(
      "The action is triggered only when the the condition is matched; if condition is null, the action is triggered immediately."
    )
    condition: Option[Condition],
    @description("Content to be shown or played. Can be text/image popup, sound or marker.")
    content: Content,
    @description("The session ID indicates a chapter, episode, etc.")
    session: Option[Session]
  )

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
