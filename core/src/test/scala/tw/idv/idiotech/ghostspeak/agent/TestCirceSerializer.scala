package tw.idv.idiotech.ghostspeak.agent

import io.circe.parser.decode
import io.circe.syntax.*
import org.apache.pekko.serialization.Serializer

// Stand-in for the example module's CirceSerializer, for the core types the MySQL tests persist.
class TestCirceSerializer extends Serializer {
  override def identifier: Int = 5567
  override def includeManifest: Boolean = true

  override def toBinary(o: AnyRef): Array[Byte] = {
    val json = o match {
      case e: Sensor.Event      => e.asJson
      case e: Sensor.State      => e.asJson
      case e: Actuator.Event[?] => e.asInstanceOf[Actuator.Event[String]].asJson
      case e: Actuator.State[?] => e.asInstanceOf[Actuator.State[String]].asJson
    }
    json.noSpaces.getBytes("UTF-8")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[?]]): AnyRef = {
    val string = new String(bytes, "UTF-8")
    val c = manifest.get
    val result =
      if (classOf[Sensor.Event].isAssignableFrom(c)) decode[Sensor.Event](string)
      else if (classOf[Sensor.State].isAssignableFrom(c)) decode[Sensor.State](string)
      else if (classOf[Actuator.Event[?]].isAssignableFrom(c))
        decode[Actuator.Event[String]](string)
      else decode[Actuator.State[String]](string)
    result.fold(throw _, identity)
  }
}
