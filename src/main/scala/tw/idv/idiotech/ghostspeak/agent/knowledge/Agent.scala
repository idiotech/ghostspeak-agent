package tw.idv.idiotech.ghostspeak.agent.knowledge

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future

class Agent extends LazyLogging {

  def actionManager = {

  }

  def actuator[T](runner: T => Future[ActionStatus], monitor: ActorRef[Event[T]]) = {
    type Result = Either[Event[T], T]
    val serviceKey = ServiceKey[Result](classOf[T].toString)
    Behaviors.setup[Result] { context =>
      context.system.receptionist ! Receptionist.Register(serviceKey, context.self)
      Behaviors.receiveMessage[Result] {
        case Right(action) =>
          context.pipeToSelf(runner(action))(_.fold(
            e => {
              logger.error(s"failed to execute action: $action", e)
              Left(Event(action, ActionStatus.Failed, System.currentTimeMillis()))
            },
            s => Left(Event(action, s, System.currentTimeMillis()))
          ))
          Behaviors.same
        case Left(event) => monitor ! event
          Behaviors.same
      }
    }
  }

}

