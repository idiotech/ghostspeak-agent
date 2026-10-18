package tw.idv.idiotech.ghostspeak

import io.circe.{ Codec, Decoder, Encoder }
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.generic.extras.Configuration
import com.typesafe.config.ConfigFactory
import tw.idv.idiotech.ghostspeak.agent.{ Action, Message }
import io.circe.config.syntax._

import scala.collection.immutable

package object daqiaotou {

  implicit val configuration: Configuration = Configuration.default
    .withDiscriminator("type")
    .withScreamingSnakeCaseConstructorNames
    .withDefaults

  case class RedisConf(host: String, port: Int)

  object RedisConf {
    implicit val codecForRedisConf: Codec.AsObject[RedisConf] = deriveConfiguredCodec[RedisConf]
  }

  case class IconConf(pending: String, arrived: String)

  object IconConf {
    implicit val codecForIconConf: Codec.AsObject[IconConf] = deriveConfiguredCodec[IconConf]
  }

  case class MysqlConf(
    host: String,
    port: Int,
    username: String,
    password: String,
    database: String
  )

  object MysqlConf {
    implicit val codecForMysqlConf: Codec.AsObject[MysqlConf] = deriveConfiguredCodec[MysqlConf]
  }

  case class DaqiaotouConf(
    engine: String = "graphscript",
    redis: RedisConf,
    icon: IconConf,
    mysql: MysqlConf
  )

  object DaqiaotouConf {

    implicit val codecForDaqiaotouConf: Codec.AsObject[DaqiaotouConf] =
      deriveConfiguredCodec[DaqiaotouConf]
  }

  lazy val config: DaqiaotouConf = {
    ConfigFactory.load().as[DaqiaotouConf]("app").fold(throw _, identity)
  }

  lazy val redis = new RedisStore(config.redis.host, config.redis.port)

  sealed trait Destination extends EnumEntry

  object Destination extends Enum[Destination] with CirceEnum[Destination] {
    val values: immutable.IndexedSeq[Destination] = findValues

    case object App extends Destination with UpperSnakecase

    case object Alert extends Destination with UpperSnakecase

    case object Intro extends Destination with UpperSnakecase

    case object Welcome extends Destination with UpperSnakecase

    case object Notification extends Destination with UpperSnakecase

    case object Dialer extends Destination with UpperSnakecase
  }

  sealed trait SoundType extends EnumEntry

  object SoundType extends Enum[SoundType] with CirceEnum[SoundType] {
    val values: immutable.IndexedSeq[SoundType] = findValues

    case object Main extends SoundType with UpperSnakecase

    case object Background extends SoundType with UpperSnakecase

    case object Loop extends SoundType with UpperSnakecase

  }

  sealed trait BeaconType extends EnumEntry

  object BeaconType extends Enum[BeaconType] with CirceEnum[BeaconType] {
    val values: immutable.IndexedSeq[BeaconType] = findValues

    case object Enter extends BeaconType with UpperSnakecase

    case object Exit extends BeaconType with UpperSnakecase

  }

  sealed trait IncomingCallType extends EnumEntry

  object IncomingCallType extends Enum[IncomingCallType] with CirceEnum[IncomingCallType] {
    val values: immutable.IndexedSeq[IncomingCallType] = findValues

    case object Connecting extends IncomingCallType with UpperSnakecase
    case object Connected extends IncomingCallType with UpperSnakecase
    case object Disconnected extends IncomingCallType with UpperSnakecase

  }

  case class Location(lat: Double, lon: Double) extends EventPayload

  object Location {
    implicit val codecForLocation: Codec.AsObject[Location] = deriveConfiguredCodec[Location]
  }

  case class MainVolume(
    speechLength: Int,
    fadeOutSeconds: Int,
    fadeInSeconds: Int = 0
  )

  object MainVolume {
    implicit val codecForMainVolume: Codec.AsObject[MainVolume] = deriveConfiguredCodec[MainVolume]
  }

  sealed trait Volume {
    val mainVolume: Option[MainVolume]
  }

  object Volume {

    case class StaticVolume(
      speechLength: Option[Int] = None,
      fadeOutSeconds: Int = 10,
      fadeInSeconds: Int = 0,
      mainVolume: Option[MainVolume]
    ) extends Volume

    case class DynamicVolume(
      center: Location,
      radius: Int,
      minVolume: Double,
      mainVolume: Option[MainVolume]
    ) extends Volume

    case class BeaconVolume(
      beaconId: String,
      minValue: Int,
      minVolume: Double,
      maxValue: Int,
      mainVolume: Option[MainVolume]
    ) extends Volume

    implicit val codecForVolume: Codec.AsObject[Volume] = deriveConfiguredCodec[Volume]
  }

  sealed trait Operation extends EnumEntry

  object Operation extends Enum[Operation] with CirceEnum[Operation] {
    val values: immutable.IndexedSeq[Operation] = findValues
    case object + extends Operation with UpperSnakecase
    case object - extends Operation with UpperSnakecase
    case object `=` extends Operation with UpperSnakecase
  }

  case class VariableUpdate(name: String, operation: Operation, value: Int) extends Task

  object VariableUpdate {

    implicit val codecForVariableUpdate: Codec.AsObject[VariableUpdate] =
      deriveConfiguredCodec[VariableUpdate]
  }

  sealed trait Task

  object Task {

    // Popup message
    // Client should show it as a notification or a popup window in the app.
    case class Popup(
      // This is the message to be shown. Can be null.
      text: Option[String] = None,
      // They are acceptable choices from the user. They can be shown as buttons or drop-down menu depending on client design.
      choices: List[String] = Nil,
      // If true, user is allowed to enter a text reply
      allowTextReply: Boolean = false,
      // Picture URL to be shown
      pictures: List[String] = Nil,
      // Destinations to be shown
      destinations: Set[Destination] = Set.empty,
      // Close alert popup after reply
      closeAlertAfterReply: Boolean = true,
      // Clear dialog
      clearDialog: Boolean = false
    ) extends Task

    // Close popup message
    // Client should close popup message
    case class PopupDismissal(
      // Destinations to be dismissed
      destinations: Set[Destination] = Set.empty
    ) extends Task

    // Incoming call
    // Client should show an incoming call message.
    case class IncomingCall(
      // The marker to delete; should be the action id that adds the marker.
      caller: String,
      status: IncomingCallType,
      portrait: String
    ) extends Task

    // Sound message
    // Client should play the sound.
    case class Sound(
      // URL of the sound file
      url: String,
      // The length of the part of the sound file that contains speech
      volumeSetting: Volume,
      // Controls whether the sound should be queued or looped
      mode: SoundType
    ) extends Task

    // Marker message
    // Client should display the marker on map.
    case class Marker(
      // The marker should be shown at this location.
      location: Location,
      // The icon image URL for the marker.
      icon: String,
      // The marker title.
      title: String,
      // The action to call on click
      onClickActionId: Option[String] = None
    ) extends Task

    // Marker removal
    // Client should remove the marker from map.
    case class MarkerRemoval(
      // The marker to delete; should be the action id that adds the marker.
      id: String
    ) extends Task

    // Map style
    // Client should change the style of the map
    case class MapStyle(
      url: Option[String],
      satellite: Boolean = false
    ) extends Task

    // Intro image
    // The background image to show
    case class IntroImage(
      backgroundUrl: Option[String],
      logoUrl: Option[String],
      logoMarginTop: Option[Float] = None,
      logoWidth: Option[Float] = None,
      logoHeight: Option[Float] = None,
      mapLogoUrl: Option[String]
    ) extends Task

    // Button style
    // The colors of a button
    case class ButtonStyle(backgroundColor: String, textColor: String) extends Task

    // Popup style
    // The colors of a popup window
    case class PopupStyle(alertTextColor: String) extends Task

    // Variable update
    // The operation to update the value of a variable
    case class VariableUpdates(updates: List[VariableUpdate]) extends Task

    // Finish the scenario
    // Client should return to homepage
    case object EndGame extends Task

    // A image on the bottom of the map to guide user to go somewhere
    // Add the guide image
    case class GuideImage(image: String) extends Task

    // guide image removal
    // Remove the guide image
    case object GuideImageRemoval extends Task

    // Silence a sound
    // Make a sound by the ID stop
    case class Silence(id: String, fadeOutSeconds: Int) extends Task

    // Add a friend
    // Add a person with name and icon
    case class Friend(id: String, name: String, icon: Option[String]) extends Task

    implicit val codecForTask: Codec.AsObject[Task] = deriveConfiguredCodec[Task]
  }

  sealed trait Condition

  object Condition {

    case object Always extends Condition

    // Geofence
    // Location condition for action.
    case class Geofence(
      // Geographic coordinates
      location: Location,
      // The radius of the geofence in meters.
      radius: Int
    ) extends Condition

    // Beacon
    // Beacon condition for action
    case class Beacon(
      // Beacon ID
      beaconId: String,
      // Threshold value for triggering
      threshold: Int,
      // Whether to activate on enter or on exit
      mode: BeaconType
    ) extends Condition

    implicit val codecForCondition: Codec.AsObject[Condition] = deriveConfiguredCodec[Condition]
  }

  case class Content(task: Task, condition: Condition, exclusiveWith: List[String] = Nil)

  object Content {
    implicit val codecForContent: Codec.AsObject[Content] = deriveConfiguredCodec[Content]
  }

  type Action = tw.idv.idiotech.ghostspeak.agent.Action[Content]
  type Message = tw.idv.idiotech.ghostspeak.agent.Message[EventPayload]

  sealed trait EventPayload

  object EventPayload {
    case class Text(text: String, asVariable: Option[String] = None) extends EventPayload
    case object GoldenFinger extends EventPayload
    case class PerformDirectly(action: Action) extends EventPayload

    implicit val codecForEventPayload: Codec.AsObject[EventPayload] =
      deriveConfiguredCodec[EventPayload]
  }

  implicit lazy val contentEncoder: Encoder.AsObject[Action] = Action.encoder[Content]
  implicit lazy val contentDecoder: Decoder[Action] = Action.decoder[Content]
  implicit lazy val messageEncoder: Encoder.AsObject[Message] = Message.encoder[EventPayload]
  implicit lazy val messageDecoder: Decoder[Message] = Message.decoder[EventPayload]

  case class PerfLog(user: String, action: String, stage: String)

  object PerfLog {
    implicit val codecForPerfLog: Codec.AsObject[PerfLog] = deriveConfiguredCodec[PerfLog]
  }
}
