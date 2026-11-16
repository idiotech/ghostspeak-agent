package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.scaladsl.PersistenceTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.Perform
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.{ Create, Query, Sense }

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** A journal write that fails (the database restarting, a connection dropped) must not stop the
  * actor for good: it restarts, recovers from the journal and serves again.
  */
class PersistFailureTest
    extends ScalaTestWithActorTestKit(PersistenceTestConfig.config)
    with AnyWordSpecLike {

  val persistence = PersistenceTestKit(system)

  // longer than the first restart backoff
  val afterRestart = 10.seconds

  def scenario(id: String) = Scenario(id, "graphscript", "[]")

  "a sensor whose journal write fails" should {

    "restart, respawn its scenarios and keep answering" in {
      val sensor = new Sensor[String]
      // scenarios the sensor (re)spawned, in order
      val spawned = new ConcurrentLinkedQueue[String]()
      val ref = spawn(
        sensor(
          "pf-sensor",
          (ctx, created) => {
            spawned.add(created.scenario.id)
            val childName =
              if (created.uniqueId.nonEmpty) created.uniqueId else UUID.randomUUID().toString
            Right(ctx.spawn(Behaviors.ignore[Sensor.Command[String]], childName))
          }
        )
      )
      val reply = createTestProbe[StatusReply[String]]()
      def query(): String = {
        ref ! Query[String](None, None, None, None, None, reply.ref)
        reply.receiveMessage(afterRestart).getValue
      }

      ref ! Create[String](scenario("s1"), reply.ref)
      reply.expectMessage(StatusReply.Success("created"))

      persistence.failNextPersisted("sensor-pf-sensor")
      ref ! Create[String](scenario("s2"), reply.ref)
      reply.expectNoMessage(500.millis)

      // still there, with what the journal holds
      val listed = query()
      listed should include("s1")
      listed should not include "s2"
      // s1's actor was stopped and spawned again under the same name; s2's never-persisted
      // actor is gone
      spawned.asScala.toList shouldBe List("s1", "s2", "s1")

      ref ! Create[String](scenario("s2"), reply.ref)
      reply.expectMessage(StatusReply.Success("created"))
      query() should include("s2")
    }
  }

  "an actuator whose journal write fails" should {

    def action(id: String) =
      Action[String](id, "user", "npc", s"content of $id", Session("scn", None))

    "restart and keep performing" in {
      val actuator = new Actuator[String, String]
      val sensor = createTestProbe[Sensor.Command[String]]()
      val ref = spawn(
        actuator.behaviorFromFuture("pf-actuator", sensor.ref, _ => Future.successful(Done))
      )

      persistence.failNextPersisted("actuator-pf-actuator")
      ref ! Perform(action("lost"), System.currentTimeMillis() + 1.hour.toMillis)

      ref ! Perform(action("now"), System.currentTimeMillis() - 1)
      sensor
        .receiveMessage(afterRestart)
        .asInstanceOf[Sense[String]]
        .message
        .actionId shouldBe Some("now")
    }

    "perform due actions once, after the restart that lets it mark them as done" in {
      val actuator = new Actuator[String, String]
      import actuator.Event.{ ActionDone, Performance }
      val sensor = createTestProbe[Sensor.Command[String]]()
      val pid = "actuator-pf-due"
      val due = Performance(action("due"), System.currentTimeMillis() - 1000)
      persistence.persistForRecovery(pid, Seq(due))
      // the ActionDone of the first attempt
      persistence.failNextPersisted(pid)

      spawn(actuator.behaviorFromFuture("pf-due", sensor.ref, _ => Future.successful(Done)))

      def modality(): Option[Modality] =
        sensor
          .receiveMessage(afterRestart)
          .asInstanceOf[Sense[String]]
          .message
          .payload
          .left
          .toOption
          .collect { case SystemPayload.Modal(m, _) => m }
      // nothing was sent by the attempt whose write failed: one Doing and one Done, no more
      Set(modality(), modality()) shouldBe Set(Some(Modality.Doing), Some(Modality.Done))
      sensor.expectNoMessage(3.seconds)
      persistence.persistedInStorage(pid).last shouldBe ActionDone(Set(due))
    }
  }

  "an actor forwarding to a child" should {

    "stop when another actor it watches stops" in {
      val probe = createTestProbe[String]()
      val ref = spawn(Behaviors.setup[String] { ctx =>
        // stands for the actuator
        val sibling = ctx.spawn(Behaviors.receiveMessage[String](_ => Behaviors.stopped), "sibling")
        ctx.watch(sibling)
        sibling ! "stop"
        Forward.toChild(ctx, Behaviors.ignore[String], "child")
      })
      probe.expectTerminated(ref)
    }

    "pass messages on and outlive the child's restarts" in {
      val sensor = new Sensor[String]
      val companion = createTestProbe[String]()
      val ref = spawn(Behaviors.setup[Sensor.Command[String]] { ctx =>
        // stands for the actuator: a child that must survive the sensor's restarts
        val sibling = ctx.spawn(
          Behaviors.receiveMessage[String] { m =>
            companion.ref ! m
            Behaviors.same
          },
          "sibling"
        )
        Forward.toChild(
          ctx,
          sensor(
            "pf-forward",
            (c, created) => {
              sibling ! created.scenario.id
              Right(c.spawn(Behaviors.ignore[Sensor.Command[String]], UUID.randomUUID().toString))
            }
          ),
          "sensor"
        )
      })
      val reply = createTestProbe[StatusReply[String]]()

      ref ! Create[String](scenario("s1"), reply.ref)
      reply.expectMessage(StatusReply.Success("created"))
      companion.expectMessage("s1")

      persistence.failNextPersisted("sensor-pf-forward")
      ref ! Create[String](scenario("s2"), reply.ref)
      companion.expectMessage("s2")
      // respawned on recovery, and the sibling is still there to hear of it
      companion.expectMessage(afterRestart, "s1")
    }

    "stop when the child stops" in {
      val probe = createTestProbe[String]()
      val ref = spawn(Behaviors.setup[String] { ctx =>
        Forward.toChild(
          ctx,
          Behaviors.receiveMessage[String] {
            case "stop" => Behaviors.stopped
            case m      =>
              probe.ref ! m
              Behaviors.same
          },
          "child"
        )
      })
      ref ! "hello"
      probe.expectMessage("hello")
      ref ! "stop"
      probe.expectTerminated(ref)
    }
  }
}
