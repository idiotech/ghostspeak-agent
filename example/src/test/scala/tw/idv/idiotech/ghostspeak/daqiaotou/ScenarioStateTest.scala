package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.{ Actuator, Message => BaseMessage, Sensor, SystemPayload }

class ScenarioStateTest extends AnyFlatSpec with Matchers {

  val creator = new ScenarioCreator(new Sensor[EventPayload], new Actuator[Content, EventPayload])

  "scenario state snapshot" must "keep variables and strings" in {
    val state = creator.State(
      Map.empty,
      Map("action-1" -> List("node-1", "node-2")),
      Map("score"    -> 3, "visits" -> 0),
      Map("name"     -> "Juliet")
    )
    decode[creator.State](state.asJson.noSpaces) mustBe Right(state)
  }

  it must "keep triggers keyed by message" in {
    val start =
      BaseMessage[EventPayload]("", None, "ghost", "user-1", Left(SystemPayload.Start), "")
    val reply = BaseMessage[EventPayload](
      "",
      Some("ask"),
      "ghost",
      "user-1",
      Right(EventPayload.Text("yes")),
      ""
    )
    val node = GraphScript.Node("n1", List(start), Nil, Nil, Set("n2"), List("n2"))
    val state = creator.State(
      Map(start -> List(node), reply -> List(node, node.copy(name = "n2"))),
      Map.empty,
      Map("score"  -> 1),
      Map("answer" -> "yes")
    )
    decode[creator.State](state.asJson.noSpaces) mustBe Right(state)
  }

  it must "refuse snapshots written without variables and strings" in {
    // Such a snapshot lost the user's variables and strings. Failing to decode it makes Pekko
    // (snapshot-is-optional = true) replay the whole journal instead of restoring empty maps.
    val legacy = """{"triggers":[],"exclusions":{"action-1":["node-1"]}}"""
    decode[creator.State](legacy).isLeft mustBe true
  }
}
