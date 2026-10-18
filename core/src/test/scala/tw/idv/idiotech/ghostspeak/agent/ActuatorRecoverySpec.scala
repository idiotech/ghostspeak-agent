package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.SnapshotTestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.Future

class ActuatorRecoverySpec
    extends ScalaTestWithActorTestKit(SensorRecoverySpec.config)
    with AnyWordSpecLike
    with Eventually {

  val actuator = new Actuator[String, String]

  "an actuator" must {

    "snapshot its pending actions once it has recorded 200 events" in {
      val name = s"snap-${UUID.randomUUID()}"
      val sensor = createTestProbe[Sensor.Command[String]]()
      val ref = spawn(actuator.behaviorFromFuture(name, sensor.ref, _ => Future.successful(())))
      val ids = (1 to 200).map(i => s"action$i")
      val later = System.currentTimeMillis() + 3600 * 1000
      ids.foreach { id =>
        ref ! Actuator.Command.Perform(Action(id, "alice", "ghost", "hi", Session("scn", None)), later)
      }

      val snapshots = SnapshotTestKit(system)
      eventually {
        val stored = snapshots.persistedInStorage(s"actuator-$name")
        stored.map(_._1.sequenceNr) shouldBe Seq(200L)
        stored.head._2.asInstanceOf[actuator.State].pendingActions.map(_.action.id) shouldBe ids.toSet
      }
    }
  }
}
