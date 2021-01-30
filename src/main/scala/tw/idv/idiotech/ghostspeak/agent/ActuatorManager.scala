package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext

trait ActuatorManager {
  def spawn(ctx: ActorContext[_], action: Action): Option[ActorRef[Actuator.Command]]
}
