package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import tw.idv.idiotech.ghostspeak.agent.Actuator._
import tw.idv.idiotech.ghostspeak.agent.Scenario.Sense

import scala.concurrent.Future
import scala.util.{Failure, Success}

class Actuator[T](discover: Discover[T], sensor: ActorRef[Scenario.Command]) {
  def apply(): Behavior[Command[T]] = Behaviors.receive { (ctx, cmd) =>
    cmd match {
      case Perform(action) =>
        discover(ctx, action, ctx.self).foreach { a =>
          a ! Perform(action)
          sensor ! Sense(action.toMessage(Modality.Doing))
        }
      case OK(action) =>
        sensor ! Sense(action.toMessage(Modality.Done))
      case KO(action, message) =>
        sensor ! Sense(action.toMessage(Modality.Failed))
    }
    Behaviors.same
  }
}

object Actuator {
  sealed trait Command[T]
  case class Perform[T](action: Action[T]) extends Command[T]
  case class OK[T](action: Action[T]) extends Command[T]
  case class KO[T](action: Action[T], reason: String) extends Command[T]
  type Discover[T] = (ActorContext[_], Action[T], ActorRef[Actuator.Command[T]]) => Option[ActorRef[Actuator.Command[T]]]
  def fromFuture[T](send: Action[T] => Future[_], replyTo: ActorRef[Actuator.Command[T]]): Behavior[Command[T]] =
    Behaviors.setup { ctx =>
      implicit val system: ActorSystem[Nothing] = ctx.system
      Behaviors.receiveMessage {
        case Perform(action) =>
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