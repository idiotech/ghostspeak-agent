package tw.idv.idiotech.ghostspeak.daqiaotou

import org.apache.pekko.actor.typed.ActorSystem

import tw.idv.idiotech.ghostspeak.agent.{ Actuator, PersistenceSettings, Sensor, Server }
import tw.idv.idiotech.ghostspeak.daqiaotou.SpotKeeper.SpotPayload

import scala.concurrent.ExecutionContext

object ServerInstance extends App {
  val sensor = new Sensor[EventPayload]
  val actuator = new Actuator[Content, EventPayload]
  val scenarioCreator = new ScenarioCreator(sensor, actuator)

  def startGraphScript(): ActorSystem[Server.Msg] =
    ActorSystem(
      Server[EventPayload](
        scenarioCreator.scenarioBehavior,
        (a, c, s) => new EventRoutes(a, c, s),
        "0.0.0.0",
        8080
      ),
      "BuildJobsServer",
      PersistenceSettings.load()
    )

  val spotKeeperSensor = new Sensor[SpotPayload]
  val spotKeeperActuator = new Actuator[Content, SpotPayload]
  val spotKeeper = new SpotKeeper(spotKeeperSensor, spotKeeperActuator)

  def startSpotKeeper(): ActorSystem[Server.Msg] =
    ActorSystem(
      Server[SpotPayload](
        spotKeeper.spotKeeperBehavior,
        (a, c, s) => new EventRoutes(a, c, s),
        "0.0.0.0",
        8081
      ),
      "BuildJobsServer",
      PersistenceSettings.load()
    )

  // The actor system ends when the server's guardian stops, which it does when the root sensor
  // or the category manager is gone for good (see Server). Exit then, so that whatever runs
  // this process starts a fresh one, rather than staying up with nothing serving.
  private def exitWhenTerminated(system: ActorSystem[?]): Unit =
    system.whenTerminated.onComplete(_ => sys.exit(1))(using ExecutionContext.global)

  if (config.engine == "graphscript") {
    exitWhenTerminated(startGraphScript())
  } else if (config.engine == "spotkeeper") {
    exitWhenTerminated(startSpotKeeper())
  } else {
    println("not a known engine")
  }

}
