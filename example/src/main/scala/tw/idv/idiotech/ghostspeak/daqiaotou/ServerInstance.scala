package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.ActorSystem
import tw.idv.idiotech.ghostspeak.agent.Server

object ServerInstance extends App {
  ActorSystem(
    Server[EventPayload](ScenarioCreator.scenarioBehavior, "localhost", 8080),
    "BuildJobsServer"
  )

}
