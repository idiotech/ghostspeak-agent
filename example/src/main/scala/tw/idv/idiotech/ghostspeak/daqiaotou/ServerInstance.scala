package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.ActorSystem
import tw.idv.idiotech.ghostspeak.agent.Server

object ServerInstance extends App {
  ActorSystem(
    Server[EventPayload](ScenarioCreator.scenarioBehavior, "0.0.0.0", 8080),
    "BuildJobsServer"
  )

}
