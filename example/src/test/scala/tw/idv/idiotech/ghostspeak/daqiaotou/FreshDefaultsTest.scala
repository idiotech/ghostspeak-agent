package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.parser.decode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.{ Message => BaseMessage, Sensor, SystemPayload }
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.Spot

// Fields whose default is computed (a random ID, the current time) must get a fresh value on
// every decode that leaves them out, not one value shared by all decodes.
class FreshDefaultsTest extends AnyFlatSpec with Matchers {

  "a Spot without an id" must "get a new id on every decode" in {
    val json = """{"title":"t","text":"x","images":[],"location":{"lat":1.0,"lon":2.0}}"""
    val ids = (1 to 3).map(_ => decode[Spot](json).toOption.get.id)
    ids.distinct.size mustBe 3
    all(ids) must startWith("SPOT-")
  }

  "a Created event without a uniqueId" must "get a new uniqueId on every decode" in {
    val json = """{"scenario":{"id":"s","engine":"graphscript","template":"[]"},"type":"CREATED"}"""
    val ids = (1 to 3).map(_ => decode[Sensor.Event](json).toOption.get.asInstanceOf[Sensor.Event.Created].uniqueId)
    ids.distinct.size mustBe 3
  }

  it must "get a new uniqueId when decoded on its own, as inside a sensor snapshot" in {
    val json = """{"scenario":{"id":"s","engine":"graphscript","template":"[]"}}"""
    val ids = (1 to 3).map(_ => decode[Sensor.Event.Created](json).toOption.get.uniqueId)
    ids.distinct.size mustBe 3
  }

  "a MODAL payload without a time" must "get the current time on every decode" in {
    val json = """{"id":"m","receiver":"ghost","sender":"u","payload":{"modality":"DONE","type":"MODAL"},"scenarioId":"s"}"""
    def time() = decode[Message](json).toOption.get.payload match {
      case Left(SystemPayload.Modal(_, t)) => t
      case other                          => fail(s"not a modal: $other")
    }
    val first = time()
    Thread.sleep(20)
    val before = System.currentTimeMillis()
    val second = time()
    second must be >= before
    second must be > first
  }

  "a MODAL payload with a time" must "keep it" in {
    val json = """{"modality":"DONE","time":42,"type":"MODAL"}"""
    decode[SystemPayload](json).toOption.get.asInstanceOf[SystemPayload.Modal].time mustBe 42L
  }
}
