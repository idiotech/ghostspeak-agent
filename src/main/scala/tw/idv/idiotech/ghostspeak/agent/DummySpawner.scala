package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import tw.idv.idiotech.ghostspeak.agent.DummySensor.Sense

object DummySpawner {

  sealed trait Command

  // 1. register
  def apply(actors: Map[String, ActorRef[DummySensor.Sense]]): Behavior[Sense] =
    Behaviors.receive {
      case (ctx, s @ Sense(event, replyTo)) =>
        actors
          .get(event.sender)
          .fold {
            val actor = ctx.spawn(DummyPlanner(event.sender), event.sender)
            actor ! s
            DummySpawner(actors + (event.sender -> actor))
          } { a =>
            a ! s
            Behaviors.same
          }
    }

}
