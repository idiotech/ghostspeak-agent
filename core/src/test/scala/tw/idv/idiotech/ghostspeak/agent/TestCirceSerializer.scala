package tw.idv.idiotech.ghostspeak.agent

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.serialization.Serializer

// Stand-in for the example module's CirceSerializer, for the core types the MySQL tests persist.
object TestActors {
  val actuator = new Actuator[String, String]
}

class TestCirceSerializer extends Serializer {
  import TestActors.actuator

  override def identifier: Int = 5567
  override def includeManifest: Boolean = true

  override def toBinary(o: AnyRef): Array[Byte] = {
    val json = o match {
      case e: Sensor.Event   => e.asJson
      case e: Sensor.State   => e.asJson
      case e: actuator.Event => e.asJson
      case e: actuator.State => e.asJson
    }
    json.noSpaces.getBytes("UTF-8")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val string = new String(bytes, "UTF-8")
    val c = manifest.get
    val result =
      if (classOf[Sensor.Event].isAssignableFrom(c)) decode[Sensor.Event](string)
      else if (classOf[Sensor.State].isAssignableFrom(c)) decode[Sensor.State](string)
      else if (classOf[actuator.Event].isAssignableFrom(c)) decode[actuator.Event](string)
      else decode[actuator.State](string)
    result.fold(throw _, identity)
  }
}
