package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import shapeless._
import shapeless.ops.hlist.RightFolder
import tw.idv.idiotech.ghostspeak.agent.util.Substitution

class ShapelessTest extends AnyFlatSpec with Matchers {

  case class Subst(oldString: String, newString: String) {
    def work(s: String): String = if (s == oldString) newString else s
  }

  object Substitutor {

    object substitute extends Poly2 {
      implicit def a[A, B <: HList]: Case.Aux[
        A,
        (B, Subst),
        (A :: B, Subst)
      ] = at[A, (B, Subst)] {
        case (col, (values, subst)) =>
          val obj: A = col match {
            case str: String => subst.work(str).asInstanceOf[A]
            case e           => e
          }
          (obj :: values, subst)
      }
    }

    def updateProduct[P <: Product, R<: HList, B<: HList](p: P, original: String, updated: String)(
      implicit aux : Generic.Aux[P, R],
      l: RightFolder.Aux[
        R,
        (HNil, Subst),
        substitute.type,
        (B, Subst)
      ]
    ): P =
      aux.from(
        aux.to(p).foldRight((HNil: HNil, Subst(original, updated)))(substitute)._1.asInstanceOf[R]
      )

  }

  "test" should "work" in {
    val ghostspeak = Ghostspeak("?a", "sheila")
    Substitution(ghostspeak, "?a", "amy") mustBe Ghostspeak("amy", "sheila")
  }

}
