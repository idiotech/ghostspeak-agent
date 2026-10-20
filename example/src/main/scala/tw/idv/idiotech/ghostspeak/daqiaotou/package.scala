package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.config.ConfigFactory
import io.circe.{ Decoder, Encoder }
import io.circe.config.syntax.*
import io.circe.derivation.{ Configuration, ConfiguredCodec }

import tw.idv.idiotech.ghostspeak.agent.{ Action, JsonEnum, Message }

given configuration: Configuration = Configuration.default
  .withDiscriminator("type")
  .withScreamingSnakeCaseConstructorNames
  .withDefaults

// timeoutMs: connect and read timeout; 0 waits indefinitely, like the redisclient it replaced
case class RedisConf(host: String, port: Int, timeoutMs: Int = 0) derives ConfiguredCodec

case class IconConf(pending: String, arrived: String) derives ConfiguredCodec

case class MysqlConf(
  host: String,
  port: Int,
  username: String,
  password: String,
  database: String
) derives ConfiguredCodec

case class DaqiaotouConf(
  engine: String = "graphscript",
  redis: RedisConf,
  icon: IconConf,
  mysql: MysqlConf
) derives ConfiguredCodec

lazy val config: DaqiaotouConf =
  ConfigFactory.load().as[DaqiaotouConf]("app").fold(throw _, identity)

lazy val redis = new RedisStore(config.redis.host, config.redis.port, config.redis.timeoutMs)

enum Destination {
  case App, Alert, Intro, Welcome, Notification, Dialer
}

object Destination extends JsonEnum[Destination]

enum SoundType {
  case Main, Background, Loop
}

object SoundType extends JsonEnum[SoundType]

enum BeaconType {
  case Enter, Exit
}

object BeaconType extends JsonEnum[BeaconType]

enum IncomingCallType {
  case Connecting, Connected, Disconnected
}

object IncomingCallType extends JsonEnum[IncomingCallType]

case class Location(lat: Double, lon: Double) extends EventPayload derives ConfiguredCodec

case class MainVolume(
  speechLength: Int,
  fadeOutSeconds: Int,
  fadeInSeconds: Int = 0
) derives ConfiguredCodec

sealed trait Volume derives ConfiguredCodec {
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
}

// how a VariableUpdate changes its variable; written as the operator in JSON
enum Operation(val symbol: String) {
  case Plus extends Operation("+")
  case Minus extends Operation("-")
  case Set extends Operation("=")
}

object Operation extends JsonEnum[Operation] {
  override def jsonName(o: Operation): String = o.symbol
}

case class VariableUpdate(name: String, operation: Operation, value: Int) extends Task
    derives ConfiguredCodec

sealed trait Task derives ConfiguredCodec

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
}

sealed trait Condition derives ConfiguredCodec

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
}

case class Content(task: Task, condition: Condition, exclusiveWith: List[String] = Nil)
    derives ConfiguredCodec

type Action = tw.idv.idiotech.ghostspeak.agent.Action[Content]
type Message = tw.idv.idiotech.ghostspeak.agent.Message[EventPayload]

sealed trait EventPayload derives ConfiguredCodec

object EventPayload {
  case class Text(text: String, asVariable: Option[String] = None) extends EventPayload
  case object GoldenFinger extends EventPayload
  case class PerformDirectly(action: Action) extends EventPayload
}

given contentEncoder: Encoder.AsObject[Action] = Action.encoder[Content]
given contentDecoder: Decoder[Action] = Action.decoder[Content]
given messageEncoder: Encoder.AsObject[Message] = Message.encoder[EventPayload]
given messageDecoder: Decoder[Message] = Message.decoder[EventPayload]

case class PerfLog(user: String, action: String, stage: String) derives ConfiguredCodec
