package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import org.apache.pekko.persistence.typed.PersistenceId
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.JsonCodec
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.{ Decoder, Encoder }
import org.virtuslab.psh.annotation.SerializabilityTrait
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.Sense

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class Actuator[T: Encoder: Decoder, P: Encoder: Decoder] extends LazyLogging {

  implicit def actionDecoder: Decoder[Action[T]] = Action.decoder[T]
  implicit def actionEncoder: Encoder[Action[T]] = Action.encoder[T]

  import Actuator._

  @ConfiguredJsonCodec
  sealed trait Event extends EventBase

  object Event {

    @JsonCodec
    case class Performance(action: Action[T], startTime: Long)
        extends Event
        with Ordered[Performance] {
      override def compare(that: Performance): Int = startTime.compare(that.startTime)
    }

    @JsonCodec
    case class ActionDone(actions: Set[Performance] = Set.empty) extends Event
  }
  type PendingAction = Event.Performance

  @JsonCodec
  case class State(pendingActions: Set[PendingAction] = Set.empty) extends EventBase

  case object TimerKey extends SerializabilityTrait
  import Command._
  import Event._

  type Discover = (
    ActorContext[_],
    Action[T],
    ActorRef[Command[T]]
  ) => Option[ActorRef[Command[T]]]

  type GenCommandHandler[ActuatorType, SensorType] =
    (
      ActorContext[Command[ActuatorType]],
      ActorRef[Sensor.Command[SensorType]],
      TimerScheduler[Command[ActuatorType]]
    ) => (State, Command[ActuatorType]) => Effect[Event, State]

  private def commandHandler(
    sensor: ActorRef[Sensor.Command[P]],
    timer: TimerScheduler[Command[T]]
  )(
    performAction: Action[T] => Unit
  )(state: State, cmd: Command[T]): Effect[Event, State] = {
    def sendAction(action: Action[T]): Unit = {
      performAction(action)
      sensor ! Sense(action.toMessage(Modality.Doing))
    }
    cmd match {
      case p @ Perform(action, startTime) =>
        val remainingTime = startTime - System.currentTimeMillis()
        if (remainingTime <= 0) {
          logger.info(s"executing immediately: ${action.id}")
          sendAction(action)
          Effect.none
        } else {
          logger.info(s"delaying execution: ${action.id}")
          val earliest =
            state.pendingActions
              .map(_.startTime)
              .find(_ > System.currentTimeMillis())
              .getOrElse(Long.MaxValue)
          if (startTime <= earliest) {
            logger.info(s"set timer for ${action.id} after ${remainingTime.millis}")
            timer.startSingleTimer(TimerKey, Timeout(), remainingTime.millis)
          } else {
            logger.info(
              s"not setting timer for ${action.id}; start time = $startTime, earliest = $earliest"
            )
          }
          Effect.persist(Performance(p.action, p.startTime))
        }
      case Timeout() =>
        val current = System.currentTimeMillis()
        logger.info(
          s"total queue at $current: ${state.pendingActions.map(x => s"${x.action.id} ${x.startTime}")}"
        )
        val toExecute = state.pendingActions.filter(_.startTime <= current)
        logger.info(s"ready to execute: ${toExecute.map(_.action.id)}")
        toExecute.toList.sorted.foreach(p => sendAction(p.action))
        state.pendingActions.toList.sorted
          .find(_.startTime > current)
          .foreach { p =>
            logger.info(
              s"set timer again for ${p.action.id} after ${(p.startTime - current).millis}"
            )
            timer.startSingleTimer(TimerKey, Timeout(), (p.startTime - current).millis)
          }
        Effect.persist(ActionDone(toExecute))
      case OK(action) =>
        sensor ! Sense(action.toMessage(Modality.Done))
        Effect.none
      case KO(action, _) =>
        sensor ! Sense(action.toMessage(Modality.Failed))
        Effect.none
    }
  }

  def eventHandler(state: State, event: Event): State = event match {
    case p: Performance =>
      logger.debug(s"adding to state: ${p.action.id}")
      val ret = State(state.pendingActions ++ Set(p))
      logger.debug(s"latest state: ${state.pendingActions.map(_.action.id)}")
      ret
    case ActionDone(actions) =>
      logger.debug(s"removing from to state: ${actions.map(_.action.id)}}")
      val ret = State(state.pendingActions diff actions)
      logger.debug(s"latest state: ${state.pendingActions.map(_.action.id)}")
      ret
    case _ =>
      logger.error(s"match error for $event")
      state
  }

  private def behavior(name: String, sensor: ActorRef[Sensor.Command[P]])(
    commandHandlerFrom: GenCommandHandler[T, P]
  ): Behavior[Command[T]] = Behaviors.withTimers { timer =>
    Behaviors.setup { ctx =>
      val commandHandler = commandHandlerFrom(ctx, sensor, timer)
      EventSourcedBehavior[Command[T], Event, State](
        persistenceId = PersistenceId.ofUniqueId(s"actuator-$name"),
        emptyState = State(),
        commandHandler = commandHandler,
        eventHandler = eventHandler
      )
    }
  }

  def behaviorFromChild(
    name: String,
    sensor: ActorRef[Sensor.Command[P]],
    discover: Discover
  ): Behavior[Command[T]] =
    behavior(name, sensor) { (ctx, sensor, timer) =>
      commandHandler(sensor, timer) { action =>
        discover(ctx, action, ctx.self).foreach { a =>
          a ! Perform(action, System.currentTimeMillis())
        }
      }
    }

  def behaviorFromFuture(
    name: String,
    sensor: ActorRef[Sensor.Command[P]],
    performFuture: Action[T] => Future[_]
  ): Behavior[Command[T]] =
    behavior(name, sensor) { (ctx, sensor, timer) =>
      commandHandler(sensor, timer) { action =>
        ctx.pipeToSelf(performFuture(action)) {
          case Success(_) =>
            if (action.receiver == "you123")
              logger.warn(s"SEND DONE: ${action.receiver} ${action.id}")
            OK(action)
          case Failure(e) =>
            KO(action, e.getMessage)
        }
      }
    }
}

object Actuator {

  sealed trait Command[T] extends CommandBase

  object Command {

    case class Perform[T](action: Action[T], startTime: Long)
        extends Command[T]
        with Ordered[Perform[T]] {
      override def compare(that: Perform[T]): Int = startTime.compare(that.startTime)
    }
    case class OK[T](action: Action[T]) extends Command[T]
    case class KO[T](action: Action[T], reason: String) extends Command[T]
    case class Timeout[T]() extends Command[T]
  }
}
