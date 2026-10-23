package tw.idv.idiotech.ghostspeak.daqiaotou

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import tw.idv.idiotech.ghostspeak.agent.{ Actuator, Metadata, Scenario, Sensor, Session }
import tw.idv.idiotech.ghostspeak.agent.Action as BaseAction
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.SpotPayload

// CirceSerializer delegates to CirceCodec, so these round-trips go through the same
// toBinary/fromBinary (and class-manifest dispatch) that Pekko uses for snapshots.
class CirceCodecTest extends AnyFlatSpec with Matchers {

  val scenarioCreator =
    new ScenarioCreator(new Sensor[EventPayload], new Actuator[Content, EventPayload])
  val graphActuator = new Actuator[Content, EventPayload]
  val spotActuator = new Actuator[Content, SpotPayload]

  val action = BaseAction[Content](
    "action_1",
    "Romeo",
    "Juliet",
    Content(
      Task.Popup(Some("Do you love me?"), List("yes", "no"), false, Nil, Set(Destination.App)),
      Condition.Geofence(Location(24.0, 120.0), 5)
    ),
    Session("romeo and juliet", Some("chapter 1"))
  )

  // with includeManifest = true, Pekko passes the class of the serialized object
  def roundTrip(codec: CirceCodec, o: AnyRef): AnyRef =
    codec.fromBinary(codec.toBinary(o), Some(o.getClass))

  "the snapshot codec" must "round-trip a user scenario state" in {
    val codec = new CirceCodec(scenarioCreator)
    val state = scenarioCreator.State(
      Map.empty,
      Map("action-1" -> List("node-1")),
      Map("score"    -> 3),
      Map("name"     -> "Juliet")
    )
    roundTrip(codec, state) mustBe state
  }

  it must "refuse a user scenario snapshot written without variables and strings" in {
    val codec = new CirceCodec(scenarioCreator)
    val legacy = """{"triggers":[],"exclusions":{}}""".getBytes("UTF-8")
    an[Exception] must be thrownBy
    codec.fromBinary(legacy, Some(classOf[scenarioCreator.State]))
  }

  it must "round-trip a sensor state" in {
    val codec = new CirceCodec(scenarioCreator)
    val created = Sensor.Event.Created(
      Scenario("s1", "graphscript", "[]", metadata = Metadata(categories = List("cat")))
    )
    val state = Sensor.State().add(created)
    roundTrip(codec, state) mustBe state
  }

  it must "round-trip an actuator state" in {
    val codec = new CirceCodec(scenarioCreator)
    val state = graphActuator.State(Set(graphActuator.Event.Performance(action, 1000L)))
    roundTrip(codec, state) mustBe state
  }

  it must "restore actuator data that any actuator can handle, whatever the engine" in {
    // The graphscript and spotkeeper actuators share Actuator.Event[Content]: what one wrote,
    // the other recovers and completes, so decoding does not depend on the engine setting.
    val codec = new CirceCodec(scenarioCreator)
    val performance = graphActuator.Event.Performance(action, 1000L)
    val restored = roundTrip(codec, graphActuator.State(Set(performance)))
      .asInstanceOf[spotActuator.State]
    val done = spotActuator.Event.ActionDone(Set(performance))
    spotActuator.eventHandler(restored, done).pendingActions mustBe empty
  }
}
