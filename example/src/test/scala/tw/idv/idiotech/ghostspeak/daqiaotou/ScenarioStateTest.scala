package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.{ Actuator, Sensor }

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

  it must "decode snapshots written without variables and strings" in {
    val legacy = """{"triggers":[],"exclusions":{"action-1":["node-1"]}}"""
    decode[creator.State](legacy) mustBe Right(
      creator.State(Map.empty, Map("action-1" -> List("node-1")))
    )
  }
}
