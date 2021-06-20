package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import tw.idv.idiotech.ghostspeak.agent.Sensor.Sense

import scala.collection.SortedSet
import scala.concurrent.Future
import scala.concurrent.duration._

import scala.util.{Failure, Success}

object Actuator {

  sealed trait Command[T]
  case class Perform[T](action: Action[T], startTime: Long) extends Command[T] with Event[T] with Ordered[Perform[T]] {
    override def compare(that: Perform[T]): Int = startTime.compare(that.startTime)
  }
  case class OK[T](action: Action[T]) extends Command[T]
  case class KO[T](action: Action[T], reason: String) extends Command[T]
  case class Timeout[T]() extends Command[T]
  sealed trait Event[T]
  type PendingAction[T] = Perform[T]
  case class ActionDone[T](actions: SortedSet[PendingAction[T]]) extends Event[T]
  type State[T] = SortedSet[PendingAction[T]]
  private case object TimerKey

  type Discover[T] = (
    ActorContext[_],
      Action[T],
      ActorRef[Actuator.Command[T]]
    ) => Option[ActorRef[Actuator.Command[T]]]

  def commandHandler[T, P](ctx: ActorContext[Command[T]], discover: Discover[T], sensor: ActorRef[Sensor.Command[P]], timer: TimerScheduler[Command[T]])(state: State[T], cmd: Command[T]) : Effect[Event[T], State[T]] = {
    def sendAction(action: Action[T]) = discover(ctx, action, ctx.self).foreach { a =>
      println("send action to child actuator")
      a ! Perform(action, System.currentTimeMillis())
      sensor ! Sense(action.toMessage(Modality.Doing))
    }

    cmd match {
      case p @ Perform(action, startTime) =>
        println(s"current time = ${System.currentTimeMillis()}; start time = $startTime")
        val remainingTime = startTime - System.currentTimeMillis()
        if (remainingTime <= 0) {
          println("executing immediately")
          sendAction(action)
          Effect.none
        } else {
          println("delaying execution")
          val earliest = state.map(_.startTime).find(_ > System.currentTimeMillis()).getOrElse(Long.MaxValue)
          if (startTime < earliest) {
            timer.startSingleTimer(TimerKey, Timeout(), remainingTime.millis)
          }
          Effect.persist(p)
        }
      case Timeout() =>
        val current = System.currentTimeMillis()
        val toExecute = state.filter(_.startTime <= current)
        println(s"ready to execute: ${toExecute}")
        toExecute.foreach(p => sendAction(p.action))
        state.find(_.startTime > current).foreach(p => timer.startSingleTimer(TimerKey, Timeout(), (p.startTime - current).millis))
        Effect.persist(ActionDone(toExecute))
      case OK(action) =>
        sensor ! Sense(action.toMessage(Modality.Done))
        Effect.none
      case KO(action, _) =>
        sensor ! Sense(action.toMessage(Modality.Failed))
        Effect.none
    }
  }

  def eventHandler[T](state: State[T], event: Event[T]): State[T] = event match {
    case p: Perform[T] => state + p
    case ActionDone(actions) => state diff actions
  }

  def apply[T, P](
    name: String,
    discover: Discover[T],
    sensor: ActorRef[Sensor.Command[P]]
  ): Behavior[Command[T]] = Behaviors.withTimers { timer =>
    Behaviors.setup { ctx =>
      EventSourcedBehavior[Command[T], Event[T], State[T]](
        persistenceId = PersistenceId.ofUniqueId(s"actuator-$name"),
        emptyState = SortedSet.empty,
        commandHandler = commandHandler(ctx, discover, sensor, timer),
        eventHandler = eventHandler
      )
    }
  }


  def fromFuture[T](
    send: Action[T] => Future[_],
    replyTo: ActorRef[Actuator.Command[T]]
  ): Behavior[Command[T]] =
    Behaviors.setup { ctx =>
      implicit val system: ActorSystem[Nothing] = ctx.system
      Behaviors.receiveMessage {
        case Perform(action, _) =>
          val future = send(action)
          ctx.pipeToSelf(future) {
            case Success(_) => OK(action)
            case Failure(e) => KO(action, e.getMessage)
          }
          Behaviors.same
        case m =>
          replyTo ! m
          Behaviors.stopped
      }
    }
}
