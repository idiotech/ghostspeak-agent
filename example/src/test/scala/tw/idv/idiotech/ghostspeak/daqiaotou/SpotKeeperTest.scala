package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.wordspec.AnyWordSpecLike

import tw.idv.idiotech.ghostspeak.agent.{ Actuator, Scenario, Sensor }
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.SpotPayload

class SpotKeeperTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("""
          pekko.persistence.journal.auto-start-journals = []
          pekko.persistence.snapshot-store.auto-start-snapshot-stores = []
          pekko.persistence.testkit.events.serialize = off
          pekko.persistence.testkit.snapshots.serialize = off
          """)
        .withFallback(EventSourcedBehaviorTestKit.config)
        .resolve()
    )
    with AnyWordSpecLike {

  val keeper = new SpotKeeper(new Sensor[SpotPayload], new Actuator[Content, SpotPayload])

  "a spot keeper's user" should {
    // the staff page asks every engine; this one keeps no variables, and must not leave the asker waiting
    "answer an inspect with no variables at once" in {
      val sensor = createTestProbe[keeper.Command]()
      val actuator = createTestProbe[Actuator.Command[Content]]()
      val user = EventSourcedBehaviorTestKit[keeper.Command, SpotKeeper.Event, SpotKeeper.State](
        system,
        keeper.ub(Scenario("u1", "spotkeeper", ""), "scn", sensor.ref, actuator.ref),
        SerializationSettings.disabled
      )
      val result = user.runCommand[StatusReply[String]](r => Sensor.Command.Inspect("scn", "u1", r))
      result.reply shouldBe StatusReply.success("""{"variables":{},"strings":{}}""")
      result.hasNoEvents shouldBe true
    }
  }
}
