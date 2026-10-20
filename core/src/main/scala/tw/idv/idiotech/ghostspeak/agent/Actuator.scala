package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.scalalogging.LazyLogging
import io.circe.{ Codec, Decoder, Encoder }
import io.circe.derivation.ConfiguredCodec
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}

import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.Sense

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

class Actuator[T: Encoder: Decoder, P: Encoder: Decoder] extends LazyLogging {

  import Actuator.*

  // Events and state are the same types for every actuator (see the companion), so any
  // actuator, whatever its engine, can decode what another one persisted.
  type Event = Actuator.Event[T]
  type State = Actuator.State[T]
  type PendingAction = Actuator.Event.Performance[T]
  val Event: Actuator.Event.type = Actuator.Event
  val State: Actuator.State.type = Actuator.State

  given eventCodec: Codec.AsObject[Event] = Actuator.Event.codecForEvent[T]
  given stateCodec: Codec.AsObject[State] = Actuator.State.codecForState[T]

  case object TimerKey
  import Actuator.Event.*
  import Command.*

  type Discover = (
    ActorContext[?],
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
          val earliest = earliestPending(state, System.currentTimeMillis())
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
          s"total queue at $current: ${state.pendingActions
              .map(x => s"${x.action.id} ${x.startTime}")}"
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
        if (toExecute.isEmpty) Effect.none else Effect.persist(ActionDone(toExecute))
      case OK(action) =>
        sensor ! Sense(action.toMessage(Modality.Done))
        Effect.none
      case KO(action, _) =>
        sensor ! Sense(action.toMessage(Modality.Failed))
        Effect.none
    }
  }

  // Start time of the next pending action that is still in the future: the one the timer is
  // currently set for. pendingActions is an unordered Set, so this must be the minimum.
  private[agent] def earliestPending(state: State, now: Long): Long =
    state.pendingActions.iterator
      .map(_.startTime)
      .filter(_ > now)
      .minOption
      .getOrElse(Long.MaxValue)

  def eventHandler(state: State, event: Event): State = event match {
    case p: Performance[T] =>
      logger.debug(s"adding to state: ${p.action.id}")
      val ret = State(state.pendingActions ++ Set(p))
      logger.debug(s"latest state: ${state.pendingActions.map(_.action.id)}")
      ret
    case ActionDone(actions) =>
      logger.debug(s"removing from to state: ${actions.map(_.action.id)}}")
      val ret = State(state.pendingActions diff actions)
      logger.debug(s"latest state: ${state.pendingActions.map(_.action.id)}")
      ret
  }

  private def behavior(name: String, sensor: ActorRef[Sensor.Command[P]], snapshotEvery: Int)(
    commandHandlerFrom: GenCommandHandler[T, P]
  ): Behavior[Command[T]] = Behaviors.withTimers { timer =>
    Behaviors.setup { ctx =>
      val commandHandler = commandHandlerFrom(ctx, sensor, timer)
      EventSourcedBehavior[Command[T], Event, State](
        persistenceId = PersistenceId.ofUniqueId(s"actuator-$name"),
        emptyState = State[T](),
        commandHandler = commandHandler,
        eventHandler = eventHandler
      ).withRetention(RetentionCriteria.snapshotEvery(snapshotEvery, 2))
    }
  }

  def behaviorFromChild(
    name: String,
    sensor: ActorRef[Sensor.Command[P]],
    discover: Discover,
    snapshotEvery: Int = Actuator.SnapshotEvery
  ): Behavior[Command[T]] =
    behavior(name, sensor, snapshotEvery) { (ctx, sensor, timer) =>
      commandHandler(sensor, timer) { action =>
        discover(ctx, action, ctx.self).foreach { a =>
          a ! Perform(action, System.currentTimeMillis())
        }
      }
    }

  def behaviorFromFuture(
    name: String,
    sensor: ActorRef[Sensor.Command[P]],
    performFuture: Action[T] => Future[?],
    snapshotEvery: Int = Actuator.SnapshotEvery
  ): Behavior[Command[T]] =
    behavior(name, sensor, snapshotEvery) { (ctx, sensor, timer) =>
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

  // the journal holds a Performance per delayed action and an ActionDone per batch performed,
  // while the state is only the actions still pending
  val SnapshotEvery = 100

  sealed trait Event[T] extends EventBase

  object Event {

    case class Performance[T](action: Action[T], startTime: Long)
        extends Event[T]
        with Ordered[Performance[T]] {
      override def compare(that: Performance[T]): Int = startTime.compare(that.startTime)
    }

    object Performance {

      given codecForPerformance[T: Encoder: Decoder]: Codec.AsObject[Performance[T]] = {
        given actionCodec: Codec[Action[T]] =
          Codec.from(Action.decoder[T], Action.encoder[T])
        Codec.AsObject.derived[Performance[T]]
      }
    }

    case class ActionDone[T](actions: Set[Performance[T]] = Set.empty[Performance[T]])
        extends Event[T]

    object ActionDone {

      given codecForActionDone[T: Encoder: Decoder]: Codec.AsObject[ActionDone[T]] =
        Codec.AsObject.derived[ActionDone[T]]
    }

    given codecForEvent[T: Encoder: Decoder]: Codec.AsObject[Event[T]] =
      ConfiguredCodec.derived[Event[T]]
  }

  case class State[T](pendingActions: Set[Event.Performance[T]] = Set.empty[Event.Performance[T]])
      extends EventBase

  object State {

    given codecForState[T: Encoder: Decoder]: Codec.AsObject[State[T]] =
      Codec.AsObject.derived[State[T]]
  }

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
