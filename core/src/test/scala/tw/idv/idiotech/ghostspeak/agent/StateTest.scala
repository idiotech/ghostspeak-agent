package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.Sensor.Event.Created
import tw.idv.idiotech.ghostspeak.agent.Sensor.Event.Destroyed

class StateTest extends AnyFlatSpec with Matchers {

  val scenario1 = Scenario(
    "s1",
    "test1",
    "template1",
    Some("scn 1"),
    None,
    false,
    None,
    1,
    Metadata(List("cat 1"))
  )

  val scenario2 = Scenario(
    "s2",
    "test2",
    "template2",
    Some("scn 2"),
    None,
    false,
    None,
    2,
    Metadata(List("cat 2"))
  )

  val scenario3 = Scenario(
    "s3",
    "test3",
    "template3",
    Some("scn 3"),
    None,
    false,
    None,
    3,
    Metadata(List("cat 1"))
  )

  val scenario4 = Scenario(
    "s4",
    "test4",
    "template4",
    Some("scn 4"),
    None,
    false,
    None,
    4,
    Metadata(List("cat 2"))
  )
  val created1 = Created(scenario1)
  val created2 = Created(scenario2)
  val created3 = Created(scenario3)
  val created4 = Created(scenario4)
  val deleted1 = Destroyed("s1")

  val state = Sensor.State().add(created1).add(created2)

  "state" must "have correct initial value" in {
    state mustBe Sensor.State(
      Map("s1"    -> created1, "s2"          -> created2),
      Map("cat 1" -> List(created1), "cat 2" -> List(created2))
    )
  }

  it must "add new scenario to the tag cache" in {
    state.add(created3) mustBe Sensor.State(
      Map("s1"    -> created1, "s2"                    -> created2, "s3" -> created3),
      Map("cat 1" -> List(created3, created1), "cat 2" -> List(created2))
    )
  }

  it must "delete new scenario from the tag cache" in {
    state.del(deleted1) mustBe Sensor.State(
      Map("s2"    -> created2),
      Map("cat 1" -> List(), "cat 2" -> List(created2))
    )
  }
}
