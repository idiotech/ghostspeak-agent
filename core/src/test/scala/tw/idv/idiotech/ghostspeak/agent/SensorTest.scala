package tw.idv.idiotech.ghostspeak.agent

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.{ Create, Destroy }
import tw.idv.idiotech.ghostspeak.agent.Sensor.Event.{ Created, Destroyed }

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

class SensorTest
    extends ScalaTestWithActorTestKit(PersistenceTestConfig.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  val sensor = new Sensor[String]
  val name = "test"
  val persistenceId = s"sensor-$name"

  // scenarios the sensor (re)spawned, in order
  val spawned = new ConcurrentLinkedQueue[String]()

  val esTestKit = EventSourcedBehaviorTestKit[Sensor.Command[String], Sensor.Event, Sensor.State](
    system,
    sensor(
      name,
      (ctx, created) => {
        spawned.add(created.scenario.id)
        val childName =
          if (created.uniqueId.nonEmpty) created.uniqueId else UUID.randomUUID().toString
        Right(ctx.spawn(Behaviors.ignore[Sensor.Command[String]], childName))
      }
    ),
    SerializationSettings.disabled
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
    esTestKit.clear()
    esTestKit.snapshotTestKit.foreach(_.clearAll())
    spawned.clear()
  }

  def scenario(id: String) = Scenario(id, "graphscript", "[]")

  def create(id: String) =
    esTestKit.runCommand[StatusReply[String]](r => Create[String](scenario(id), r))

  "Sensor" should {

    "persist Created for a new scenario" in {
      val result = create("s1")
      result.reply shouldBe StatusReply.Success("created")
      result.eventOfType[Created].scenario shouldBe scenario("s1")
      result.state.scenarios.keySet shouldBe Set("s1")
    }

    "reject a duplicate scenario without persisting" in {
      create("s1")
      val result = create("s1")
      result.reply.isError shouldBe true
      result.hasNoEvents shouldBe true
    }

    "persist Destroyed and drop the scenario" in {
      create("s1")
      val result = esTestKit.runCommand[StatusReply[String]](r => Destroy[String]("s1", r))
      result.event shouldBe Destroyed("s1")
      result.state.scenarios shouldBe empty
    }

    "snapshot every 100 events and respawn every scenario after recovery" in {
      val ids = (1 to 101).map(i => s"s$i")
      ids.foreach(create)

      val snapshots = esTestKit.snapshotTestKit.get.persistedInStorage(persistenceId)
      snapshots.map(_._1.sequenceNr) shouldBe Seq(100L)
      snapshots.head._2.asInstanceOf[Sensor.State].scenarios.size shouldBe 100

      spawned.clear()
      val restored = esTestKit.restart().state
      restored.scenarios.keySet shouldBe ids.toSet
      spawned.asScala.toSet shouldBe ids.toSet
    }
  }

  "Sensor state" should {
    "survive a JSON round-trip, as used for snapshots" in {
      val c1 = Created(scenario("s1").copy(metadata = Metadata(categories = List("cat"))))
      val c2 = Created(scenario("s2"))
      val state = Sensor.State().add(c1).add(c2)
      decode[Sensor.State](state.asJson.noSpaces) shouldBe Right(state)
    }
  }
}
