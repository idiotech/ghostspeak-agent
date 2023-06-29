package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.actor.typed.ActorSystem
import tw.idv.idiotech.ghostspeak.agent.{ Actuator, Sensor, Server }
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.SpotPayload

object ServerInstance extends App {
  val sensor = new Sensor[EventPayload]
  val actuator = new Actuator[Content, EventPayload]
  val scenarioCreator = new ScenarioCreator(sensor, actuator)

  def startGraphScript(): Unit =
    ActorSystem(
      Server[EventPayload](
        scenarioCreator.scenarioBehavior,
        (a, c, s) => new EventRoutes(a, c, s),
        "0.0.0.0",
        8080
      ),
      "BuildJobsServer"
    )

  val spotKeeperSensor = new Sensor[SpotPayload]
  val spotKeeperActuator = new Actuator[Content, SpotPayload]
  val spotKeeper = new SpotKeeper(spotKeeperSensor, spotKeeperActuator)

  def startSpotKeeper(): Unit =
    ActorSystem(
      Server[SpotPayload](
        spotKeeper.spotKeeperBehavior,
        (a, c, s) => new EventRoutes(a, c, s),
        "0.0.0.0",
        8081
      ),
      "BuildJobsServer"
    )

  if (config.engine == "graphscript") {
    startGraphScript()
  } else if (config.engine == "spotkeeper") {
    startSpotKeeper()
  } else {
    println("not a known engine")
  }

}
