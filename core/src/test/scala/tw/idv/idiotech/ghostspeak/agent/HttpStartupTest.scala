package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration.*

// Pekko HTTP refuses to start when its modules on the classpath have mixed versions (for
// example a library pulling in an older pekko-http-spray-json). Starting it here makes that a
// test failure rather than a server that dies at startup.
class HttpStartupTest extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "Pekko HTTP" should {
    "start with the project's dependencies" in {
      val binding =
        Await.result(Http().newServerAt("127.0.0.1", 0).bind(complete("ok")), 10.seconds)
      binding.localAddress.getPort should be > 0
      Await.result(binding.unbind(), 10.seconds)
    }
  }
}
