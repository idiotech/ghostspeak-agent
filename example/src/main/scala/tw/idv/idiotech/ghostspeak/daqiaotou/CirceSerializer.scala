package tw.idv.idiotech.ghostspeak.daqiaotou

import akka.serialization.Serializer
import io.circe
import io.circe.Json
import io.circe.syntax._
import io.circe.parser.decode
import tw.idv.idiotech.ghostspeak.agent.EventBase
import tw.idv.idiotech.ghostspeak.agent
import tw.idv.idiotech.ghostspeak.daqiaotou

class CirceSerializer extends Serializer {
  override def identifier: Int = 5566

  override def includeManifest: Boolean = true

  import daqiaotou.ServerInstance.scenarioCreator
  import daqiaotou.ServerInstance.actuator

  override def toBinary(o: AnyRef): Array[Byte] = {
    val json: Json = o match {
      case eb: EventBase =>
        eb match {
          case e: scenarioCreator.Event => e.asJson
          case e: scenarioCreator.State => e.asJson
          case e: actuator.Event        => e.asJson
          case e: actuator.State        => e.asJson
          case e: agent.Sensor.Event    => e.asJson
          case e: agent.Sensor.State    => e.asJson
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
    val string = new String(bytes, "UTF-8")
//    val r1: Option[Either[circe.Error, EventBase]] = manifest.map(c => c match {
//      case `sce` => decode[scenarioCreator.Event](string)
//      case `scs` => decode[scenarioCreator.State](string)
//      case `ae` => decode[actuator.Event](string)
//      case `as` => decode[actuator.State](string)
//      case `se` => decode[agent.Sensor.Event](string)
//      case `ss` => decode[agent.Sensor.State](string)
//    })
    val result = manifest.map(c =>
      if (sce.isAssignableFrom(c)) decode[scenarioCreator.Event](string)
      else if (scs.isAssignableFrom(c)) decode[scenarioCreator.State](string)
      else if (ae.isAssignableFrom(c)) decode[actuator.Event](string)
      else if (as.isAssignableFrom(c)) decode[actuator.State](string)
      else if (se.isAssignableFrom(c)) decode[agent.Sensor.Event](string)
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
