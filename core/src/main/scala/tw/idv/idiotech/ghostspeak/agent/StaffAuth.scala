package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.ConfigFactory
import io.circe.Codec

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** Issues and checks staff tokens: proof that the caller knew the shared staff code.
  *
  * A token is `<expiry epoch millis>.<signature>`, signed with a key derived from the code, so the
  * server keeps no state and changing the code revokes every token. `code` empty or absent turns
  * staff access off.
  */
class StaffAuth(code: Option[String], now: () => Long = () => System.currentTimeMillis()) {
  import StaffAuth.*

  private val codeBytes: Option[Array[Byte]] = code.filter(_.nonEmpty).map(_.getBytes(UTF_8))

  private val key: Option[SecretKeySpec] = codeBytes.map { c =>
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("ghostspeak-staff:".getBytes(UTF_8))
    new SecretKeySpec(digest.digest(c), "HmacSHA256")
  }

  private def sign(k: SecretKeySpec, expiresAt: Long): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(k)
    mac.doFinal(s"staff:$expiresAt".getBytes(UTF_8))
  }

  def issue(candidate: String): Option[Token] =
    for {
      k <- key
      c <- codeBytes
      if MessageDigest.isEqual(c, candidate.getBytes(UTF_8))
    } yield {
      val expiresAt = now() + Lifetime
      Token(s"$expiresAt.${encoder.encodeToString(sign(k, expiresAt))}", expiresAt)
    }

  def isValid(token: String): Boolean =
    key.exists { k =>
      token.split('.') match {
        case Array(expiry, signature) =>
          (for {
            expiresAt <- expiry.toLongOption
            presented <- Try(decoder.decode(signature)).toOption
          } yield now() < expiresAt && MessageDigest.isEqual(presented, sign(k, expiresAt)))
            .getOrElse(false)
        case _ => false
      }
    }
}

object StaffAuth {

  val Lifetime: Long = 12L * 3600 * 1000

  case class Token(token: String, expiresAt: Long) derives Codec.AsObject

  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def fromConfig(): StaffAuth =
    StaffAuth(Try(ConfigFactory.load().getString("app.staffCode")).toOption)
}
