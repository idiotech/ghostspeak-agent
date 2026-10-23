package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import org.apache.pekko.serialization.Serializer

import tw.idv.idiotech.ghostspeak.{ agent, daqiaotou }
import tw.idv.idiotech.ghostspeak.agent.{ Actuator, CategoryManager, EventBase }

class CirceSerializer extends Serializer {
  override def identifier: Int = 5566

  override def includeManifest: Boolean = true

  private lazy val codec = new CirceCodec(ServerInstance.scenarioCreator)

  override def toBinary(o: AnyRef): Array[Byte] = codec.toBinary(o)

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[?]]): AnyRef =
    codec.fromBinary(bytes, manifest)
}

// Actuator events and state are Actuator.Event[Content] / Actuator.State[Content] for every
// engine, so they decode the same way whichever engine persisted them.
class CirceCodec(val scenarioCreator: ScenarioCreator) {

  def toBinary(o: AnyRef): Array[Byte] = {
    val json: Json = o match {
      case eb: EventBase =>
        eb match {
          case e: scenarioCreator.Event => e.asJson
          case e: scenarioCreator.State => e.asJson
          // the type argument is erased; every actuator these engines persist acts on Content
          case e: Actuator.Event[?]     => e.asInstanceOf[Actuator.Event[Content]].asJson
          case e: Actuator.State[?]     => e.asInstanceOf[Actuator.State[Content]].asJson
          case e: agent.Sensor.Event    => e.asJson
          case e: agent.Sensor.State    => e.asJson
          case e: SpotKeeper.Event      => e.asJson
          case e: SpotKeeper.State      => e.asJson
          case e: CategoryManager.Event => e.asJson
          case e: CategoryManager.State => e.asJson
        }
    }
    json.toString.getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[?]]): AnyRef = {
    val sce = classOf[scenarioCreator.Event]
    val scs = classOf[scenarioCreator.State]
    val ae = classOf[Actuator.Event[?]]
    val as = classOf[Actuator.State[?]]
    val se = classOf[agent.Sensor.Event]
    val ss = classOf[agent.Sensor.State]
    val spe = classOf[daqiaotou.SpotKeeper.Event]
    val sps = classOf[daqiaotou.SpotKeeper.State]
    val ce = classOf[CategoryManager.Event]
    val cs = classOf[CategoryManager.State]
    val string = new String(bytes, "UTF-8")
    val result = manifest.map(c =>
      if (sce.isAssignableFrom(c)) decode[scenarioCreator.Event](string)
      else if (scs.isAssignableFrom(c)) decode[scenarioCreator.State](string)
      else if (ae.isAssignableFrom(c)) decode[Actuator.Event[Content]](string)
      else if (as.isAssignableFrom(c)) decode[Actuator.State[Content]](string)
      else if (se.isAssignableFrom(c)) decode[agent.Sensor.Event](string)
      else if (ss.isAssignableFrom(c)) decode[agent.Sensor.State](string)
      else if (spe.isAssignableFrom(c)) decode[daqiaotou.SpotKeeper.Event](string)
      else if (sps.isAssignableFrom(c)) decode[daqiaotou.SpotKeeper.State](string)
      else if (ce.isAssignableFrom(c)) decode[CategoryManager.Event](string)
      else if (cs.isAssignableFrom(c)) decode[CategoryManager.State](string)
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
