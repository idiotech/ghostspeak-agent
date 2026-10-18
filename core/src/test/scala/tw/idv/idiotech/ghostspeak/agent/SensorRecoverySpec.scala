package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.persistence.testkit.scaladsl.{ PersistenceTestKit, SnapshotTestKit }
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration._

object SensorRecoverySpec {

  val config = PersistenceTestKitPlugin.config
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
    .withFallback(ConfigFactory.parseString("""
      pekko.persistence.testkit.events.serialize = off
      pekko.persistence.testkit.snapshots.serialize = off
    """))
}

class SensorRecoverySpec
    extends ScalaTestWithActorTestKit(SensorRecoverySpec.config)
    with AnyWordSpecLike
    with Eventually {

  val sensor = new Sensor[String]

  // spawns a child per user that reports its creation and forwards every command it gets
  class Users {
    val spawned = createTestProbe[String]()
    val received = createTestProbe[(String, Sensor.Command[String])]()

    val creator: sensor.CreatorScenarioActor = (ctx, created) => {
      val user = created.scenario.id
      spawned.ref ! user
      val child: Behavior[Sensor.Command[String]] = Behaviors.receiveMessage { cmd =>
        received.ref ! (user -> cmd)
        Behaviors.same
      }
      Right(ctx.spawn(child, user))
    }
  }

  def perUserSensor(name: String, users: Users) =
    spawn(sensor(name, users.creator, sensor.onCommandPerUser, eagerChildren = false))

  def sense(user: String) =
    Sensor.Command.Sense[String](
      Message("m", None, "ghost", user, Right("hello"), "scn")
    )

  def restart(name: String, users: Users, ref: org.apache.pekko.actor.typed.ActorRef[_]) = {
    testKit.stop(ref)
    perUserSensor(name, users)
  }

  def journal(name: String) = PersistenceTestKit(system).persistedInStorage(s"sensor-$name")

  "a sensor with eager children" must {

    "spawn known users when it recovers" in {
      val name = s"eager-${UUID.randomUUID()}"
      val before = new Users
      val first = spawn(sensor(name, before.creator, sensor.onCommandPerUser))
      first ! sense("alice")
      before.spawned.expectMessage("alice")

      val after = new Users
      testKit.stop(first)
      spawn(sensor(name, after.creator, sensor.onCommandPerUser))
      after.spawned.expectMessage("alice")
    }
  }

  "a sensor" must {

    "snapshot its state once it has recorded 100 events" in {
      val name = s"snap-${UUID.randomUUID()}"
      val users = new Users
      val ref = perUserSensor(name, users)
      val names = (1 to 100).map(i => s"user$i")
      names.foreach(n => ref ! sense(n))
      users.spawned.receiveMessages(100)

      val snapshots = SnapshotTestKit(system)
      eventually {
        val stored = snapshots.persistedInStorage(s"sensor-$name")
        stored.map(_._1.sequenceNr) shouldBe Seq(100L)
        stored.head._2.asInstanceOf[Sensor.State].scenarios.keySet shouldBe names.toSet
      }
    }
  }

  "a per-user sensor with lazy children" must {

    "not spawn known users when it recovers" in {
      val name = s"lazy-${UUID.randomUUID()}"
      val before = new Users
      val first = perUserSensor(name, before)
      first ! sense("alice")
      before.spawned.expectMessage("alice")

      val after = new Users
      restart(name, after, first)
      after.spawned.expectNoMessage(500.millis)
    }

    "wake a known user on its next message without recording it again" in {
      val name = s"lazy-${UUID.randomUUID()}"
      val before = new Users
      val first = perUserSensor(name, before)
      first ! sense("alice")
      before.spawned.expectMessage("alice")

      val after = new Users
      val second = restart(name, after, first)
      second ! sense("alice")
      after.spawned.expectMessage("alice")
      after.received.expectMessage(("alice", sense("alice")))
      journal(name).size shouldBe 1
    }

    "deliver a broadcast to every known user, running or not" in {
      val name = s"lazy-${UUID.randomUUID()}"
      val before = new Users
      val first = perUserSensor(name, before)
      first ! sense("alice")
      first ! sense("bob")
      before.spawned.receiveMessages(2)

      val after = new Users
      val second = restart(name, after, first)
      val news = Message[String]("b", None, "", "ghost", Right("news"), "scn")
      second ! Sensor.Command.Broadcast(news)
      after.received.receiveMessages(2).toSet shouldBe Set(
        "alice" -> Sensor.Command.Sense(news.copy(receiver = "alice")),
        "bob"   -> Sensor.Command.Sense(news.copy(receiver = "bob"))
      )
    }
  }
}
