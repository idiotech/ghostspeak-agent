package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

class Actuator(actuatorManager: ActuatorManager) {
  import Actuator._
  def apply(): Behavior[Act] = Behaviors.receive {
    (ctx, cmd) => actuatorManager.spawn(ctx, cmd.action)
  }
  // accept, spawn according to type, send to it
  // state? => no state
}
object Actuator {
  sealed trait Command
  case class Act(action: Action)
  sealed trait Event

}