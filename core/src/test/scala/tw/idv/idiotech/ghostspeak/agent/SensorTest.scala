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

  "Sensor" should {
    "snapshot at the interval it is given" in {
      val perUser = EventSourcedBehaviorTestKit[Sensor.Command[String], Sensor.Event, Sensor.State](
        system,
        sensor(
          "per-user",
          (ctx, _) =>
            Right(ctx.spawn(Behaviors.ignore[Sensor.Command[String]], UUID.randomUUID().toString)),
          snapshotEvery = 10
        ),
        SerializationSettings.disabled
      )
      (1 to 25).foreach(i =>
        perUser.runCommand[StatusReply[String]](r => Create[String](scenario(s"u$i"), r))
      )
      perUser.snapshotTestKit.get
        .persistedInStorage("sensor-per-user")
        .map(_._1.sequenceNr) shouldBe
      Seq(10L, 20L)
    }
  }

  "Sensor state" should {
    def categorised(id: String, categories: String*) =
      Created(scenario(id).copy(metadata = Metadata(categories = categories.toList)))

    "survive a JSON round-trip, as used for snapshots" in {
      val state = Sensor
        .State()
        .add(categorised("s1", "cat", "other"))
        .add(categorised("s2", "cat"))
        .add(categorised("s3"))
      val restored = decode[Sensor.State](state.asJson.noSpaces).toOption.get
      restored.scenarios shouldBe state.scenarios
      restored.scenariosByCategory.view.mapValues(_.toSet).toMap shouldBe
      state.scenariosByCategory.view.mapValues(_.toSet).toMap
    }

    "keep each category's order, as replaying the journal builds it" in {
      // newest first; re-adding a scenario moves it to the front
      val ids = (1 to 40).map(i => s"s$i")
      val state = ids.foldLeft(Sensor.State())((s, id) => s.add(categorised(id, "cat")))
        .add(categorised("s7", "cat"))
      val restored = decode[Sensor.State](state.asJson.noSpaces).toOption.get
      restored.scenariosByCategory("cat").map(_.scenario.id) shouldBe
        state.scenariosByCategory("cat").map(_.scenario.id)
      restored shouldBe state
    }

    "decode a snapshot that has no category lists (written before they were stored)" in {
      val state = Sensor.State().add(categorised("s1", "a", "b")).add(categorised("s2", "a"))
      val legacy = state.asJson.hcursor.downField("categories").delete.top.get
      val restored = legacy.as[Sensor.State].toOption.get
      restored.scenarios shouldBe state.scenarios
      restored.scenariosByCategory.view.mapValues(_.toSet).toMap shouldBe
        state.scenariosByCategory.view.mapValues(_.toSet).toMap
    }

    "store each scenario once, not again per category" in {
      val state = Sensor.State().add(categorised("s1", "a", "b", "c"))
      val json = state.asJson
      json.hcursor.keys.get.toList shouldBe List("scenarios", "categories")
      json.hcursor.downField("categories").focus.get shouldBe
        io.circe.parser.parse("""{"a":["s1"],"b":["s1"],"c":["s1"]}""").toOption.get
      // the scenario itself appears once: map key + scenario.id (+ one id per category)
      "\"s1\"".r.findAllMatchIn(json.noSpaces).size shouldBe 5
    }
  }
}
