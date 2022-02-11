package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.serialization.Serializer
import io.circe.Json
import io.circe.syntax._
import io.circe.parser.decode
import tw.idv.idiotech.ghostspeak.agent.EventBase
import tw.idv.idiotech.ghostspeak.{ agent, daqiaotou }

class CirceSerializer extends Serializer {
  override def identifier: Int = 5566

  override def includeManifest: Boolean = true

  import daqiaotou.ServerInstance.scenarioCreator
  import daqiaotou.ServerInstance.actuator
  import daqiaotou.ServerInstance.spotKeeperActuator

  override def toBinary(o: AnyRef): Array[Byte] = {
    val json: Json = o match {
      case eb: EventBase =>
        eb match {
          case e: scenarioCreator.Event    => e.asJson
          case e: scenarioCreator.State    => e.asJson
          case e: actuator.Event           => e.asJson
          case e: actuator.State           => e.asJson
          case e: agent.Sensor.Event       => e.asJson
          case e: agent.Sensor.State       => e.asJson
          case e: SpotKeeper.Event         => e.asJson
          case e: SpotKeeper.State         => e.asJson
          case e: spotKeeperActuator.Event => e.asJson
          case e: spotKeeperActuator.State => e.asJson
        }
    }
    json.toString.getBytes("UTF-8")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val sce = classOf[scenarioCreator.Event]
    val scs = classOf[scenarioCreator.State]
    val ae = classOf[actuator.Event]
    val as = classOf[actuator.State]
    val se = classOf[agent.Sensor.Event]
    val ss = classOf[agent.Sensor.State]
    val spe = classOf[daqiaotou.SpotKeeper.Event]
    val sps = classOf[daqiaotou.SpotKeeper.State]
    val spae = classOf[spotKeeperActuator.Event]
    val spas = classOf[spotKeeperActuator.State]
    val string = new String(bytes, "UTF-8")
    val result = manifest.map(c =>
      if (sce.isAssignableFrom(c)) decode[scenarioCreator.Event](string)
      else if (scs.isAssignableFrom(c)) decode[scenarioCreator.State](string)
      else if (ae.isAssignableFrom(c)) decode[actuator.Event](string)
      else if (as.isAssignableFrom(c)) decode[actuator.State](string)
      else if (se.isAssignableFrom(c)) decode[agent.Sensor.Event](string)
      else if (ss.isAssignableFrom(c)) decode[agent.Sensor.State](string)
      else if (spe.isAssignableFrom(c)) decode[daqiaotou.SpotKeeper.Event](string)
      else if (sps.isAssignableFrom(c)) decode[daqiaotou.SpotKeeper.State](string)
      else if (spae.isAssignableFrom(c)) decode[spotKeeperActuator.Event](string)
      else if (spas.isAssignableFrom(c)) decode[spotKeeperActuator.State](string)
      else decode[agent.Sensor.State](string)
    )
    result.fold {
      throw new Exception("no manifest found when decoding")
    } {
      _.fold(
        error => throw error,
        identity
      )
    }
  }
}
