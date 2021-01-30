package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext

trait ScenarioManager {
  def createScenario(ctx: ActorContext[_], id: String, template: String) : Option[ActorRef[Scenario.Command]]
}