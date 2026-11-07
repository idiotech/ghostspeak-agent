package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StaffAuthTest extends AnyWordSpec with Matchers {

  private val start = 1_800_000_000_000L
  private def auth(code: Option[String], at: Long = start) = StaffAuth(code, () => at)

  "StaffAuth" should {
    "issue a token for the right code that it then accepts" in {
      val token = auth(Some("right")).issue("right")
      token.map(_.expiresAt) shouldBe Some(start + StaffAuth.Lifetime)
      token.exists(t => auth(Some("right")).isValid(t.token)) shouldBe true
    }

    "refuse a wrong or empty code" in {
      auth(Some("right")).issue("wrong") shouldBe None
      auth(Some("right")).issue("") shouldBe None
    }

    "issue nothing when staff access is off" in {
      auth(None).issue("") shouldBe None
      auth(None).issue("anything") shouldBe None
      auth(Some("")).issue("") shouldBe None
    }

    "accept no token when staff access is off" in {
      val token = auth(Some("right")).issue("right").get.token
      auth(None).isValid(token) shouldBe false
      auth(Some("")).isValid(token) shouldBe false
    }

    "refuse an expired token" in {
      val token = auth(Some("right")).issue("right").get
      auth(Some("right"), token.expiresAt - 1).isValid(token.token) shouldBe true
      auth(Some("right"), token.expiresAt).isValid(token.token) shouldBe false
    }

    "refuse a token signed with an earlier code" in {
      val token = auth(Some("old")).issue("old").get.token
      auth(Some("new")).isValid(token) shouldBe false
    }

    "refuse a token whose expiry was moved later" in {
      val token = auth(Some("right")).issue("right").get
      val signature = token.token.split('.')(1)
      auth(Some("right")).isValid(s"${token.expiresAt + 1000000}.$signature") shouldBe false
    }

    "refuse malformed tokens without throwing" in {
      val a = auth(Some("right"))
      List("", "garbage", "abc.def", "1.2.3", ".", "123.", ".abc", "9" * 40 + ".abc", "123.***")
        .foreach(t => withClue(t)(a.isValid(t) shouldBe false))
    }
  }
}
