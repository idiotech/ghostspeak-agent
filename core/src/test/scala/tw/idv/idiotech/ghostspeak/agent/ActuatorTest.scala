package tw.idv.idiotech.ghostspeak.agent

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.{ Perform, Timeout }
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.Sense

import scala.concurrent.Future
import scala.concurrent.duration._

class ActuatorTest
    extends ScalaTestWithActorTestKit(PersistenceTestConfig.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  val actuator = new Actuator[String, String]
  import actuator.Event.{ ActionDone, Performance }

  val name = "test"
  val persistenceId = s"actuator-$name"
  val sensor = createTestProbe[Sensor.Command[String]]()

  val esTestKit = EventSourcedBehaviorTestKit[
    Actuator.Command[String],
    actuator.Event,
    actuator.State
  ](
    system,
    actuator.behaviorFromFuture(name, sensor.ref, _ => Future.successful(Done)),
    SerializationSettings.disabled
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
    esTestKit.clear()
    esTestKit.snapshotTestKit.foreach(_.clearAll())
  }

  def action(id: String) =
    Action[String](id, "user", "npc", s"content of $id", Session("scn", None))
  def inOneHour(offset: Long = 0) = System.currentTimeMillis() + 1.hour.toMillis + offset

  "Actuator" should {

    "persist a Performance for a delayed action" in {
      val startTime = inOneHour()
      val result = esTestKit.runCommand(Perform(action("a1"), startTime))
      result.event shouldBe Performance(action("a1"), startTime)
      result.state.pendingActions shouldBe Set(Performance(action("a1"), startTime))
      sensor.expectNoMessage(100.millis)
    }

    "perform an action that is already due without persisting it" in {
      val result = esTestKit.runCommand(Perform(action("now"), System.currentTimeMillis() - 1))
      result.hasNoEvents shouldBe true
      result.state.pendingActions shouldBe empty
      def nextModality() = {
        val message = sensor.expectMessageType[Sense[String]].message
        message.actionId shouldBe Some("now")
        message.payload.left.toOption.collect { case SystemPayload.Modal(m, _) => m }
      }
      nextModality() shouldBe Some(Modality.Doing)
      // then Done, once performAction's future completes
      nextModality() shouldBe Some(Modality.Done)
    }

    "not persist anything on Timeout when no action is due" in {
      esTestKit.runCommand(Perform(action("later"), inOneHour()))
      val result = esTestKit.runCommand(Timeout[String]())
      result.hasNoEvents shouldBe true
      result.state.pendingActions.map(_.action.id) shouldBe Set("later")
      sensor.expectNoMessage(100.millis)
    }

    "not persist anything on Timeout when nothing is pending" in {
      val result = esTestKit.runCommand(Timeout[String]())
      result.hasNoEvents shouldBe true
      esTestKit.persistenceTestKit.expectNothingPersisted(persistenceId)
    }

    "perform due actions on Timeout and persist them as done" in {
      // a pending action whose start time passed while the server was down
      val due = Performance(action("due"), System.currentTimeMillis() - 1000)
      val later = Performance(action("later"), inOneHour())
      esTestKit.persistenceTestKit.persistForRecovery(persistenceId, Seq(due, later))
      esTestKit.restart().state.pendingActions shouldBe Set(due, later)

      val result = esTestKit.runCommand(Timeout[String]())
      result.event shouldBe ActionDone(Set(due))
      result.state.pendingActions shouldBe Set(later)
      sensor.expectMessageType[Sense[String]].message.actionId shouldBe Some("due") // Doing
      sensor.expectMessageType[Sense[String]].message.actionId shouldBe Some("due") // Done
    }

    "snapshot every 100 events and recover pending actions from it" in {
      val startTimes = (1 to 101).map(i => inOneHour(i.toLong))
      startTimes.zipWithIndex.foreach { case (t, i) =>
        esTestKit.runCommand(Perform(action(s"a$i"), t))
      }

      val snapshots = esTestKit.snapshotTestKit.get.persistedInStorage(persistenceId)
      snapshots.map(_._1.sequenceNr) shouldBe Seq(100L)
      snapshots.head._2.asInstanceOf[actuator.State].pendingActions.size shouldBe 100

      // recovery = snapshot at 100 + the one event after it
      val restored = esTestKit.restart().state
      restored.pendingActions.size shouldBe 101
      restored.pendingActions.map(_.startTime) shouldBe startTimes.toSet
    }
  }

  "Actuator" should {
    "snapshot at the interval it is given" in {
      val every10 = EventSourcedBehaviorTestKit[Actuator.Command[String], actuator.Event, actuator.State](
        system,
        actuator.behaviorFromFuture("every-10", sensor.ref, _ => Future.successful(Done), snapshotEvery = 10),
        SerializationSettings.disabled
      )
      (1 to 25).foreach(i => every10.runCommand(Perform(action(s"a$i"), inOneHour(i.toLong))))
      every10.snapshotTestKit.get.persistedInStorage("actuator-every-10").map(_._1.sequenceNr) shouldBe
        Seq(10L, 20L)
      every10.restart().state.pendingActions.size shouldBe 25
    }
  }

  "earliestPending" should {
    "return the earliest future start time, whatever the Set's iteration order" in {
      val now = 1000000L
      // more than 4 elements, so pendingActions is a HashSet with no particular order
      val startTimes = Seq(now + 600, now + 300, now + 900, now + 100, now + 700, now + 500)
      val pending = startTimes.zipWithIndex.map { case (t, i) => Performance(action(s"a$i"), t) }
      val state = actuator.State((pending :+ Performance(action("past"), now - 50)).toSet)
      actuator.earliestPending(state, now) shouldBe now + 100
    }

    "be Long.MaxValue when nothing is pending in the future" in {
      val state = actuator.State(Set(Performance(action("past"), 10L)))
      actuator.earliestPending(state, 1000L) shouldBe Long.MaxValue
    }
  }

  "Actuator state" should {
    "survive a JSON round-trip, as used for snapshots" in {
      val state = actuator.State(
        Set(Performance(action("a1"), 1000L), Performance(action("a2"), 2000L))
      )
      decode[actuator.State](state.asJson.noSpaces) shouldBe Right(state)
    }
  }
}
