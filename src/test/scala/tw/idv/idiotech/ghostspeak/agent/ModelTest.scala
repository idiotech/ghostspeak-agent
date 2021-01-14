package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import io.circe.syntax._

class ModelTest extends AnyFlatSpec with Matchers {

  "model" must "translate to json" in {
    val action = Action(
      "Romeo",
      "Juliet",
      Some(Geofence(24.0, 120.0, 5)),
      Content.Text(
        "Do you love me?",
        List("yes", "no"),
        false,
        Set(Destination.Notification)
      )
    )
    println(action.asJson)

  }

}
