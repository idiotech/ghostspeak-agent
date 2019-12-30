package tw.idv.idiotech.ghostspeak.agent.util

import shapeless._
import shapeless.ops.hlist.RightFolder

object Substitution {
  case class SubstPair(oldString: String, newString: String) {
    def work(s: String): String = if (s == oldString) newString else s
  }

  object substitute extends Poly2 {
    implicit def a[A, B <: HList]: Case.Aux[
      A,
      (B, SubstPair),
      (A :: B, SubstPair)
    ] = at[A, (B, SubstPair)] {
      case (col, (values, subst)) =>
        val obj: A = col match {
          case str: String => subst.work(str).asInstanceOf[A]
          case e           => e
        }
        (obj :: values, subst)
    }
  }

  def apply[P <: Product, R<: HList, B<: HList](p: P, original: String, updated: String)(
    implicit aux : Generic.Aux[P, R],
    l: RightFolder.Aux[
      R,
      (HNil, SubstPair),
      substitute.type,
      (B, SubstPair)
    ]
  ): P =
    aux.from(
      aux.to(p).foldRight((HNil: HNil, SubstPair(original, updated)))(substitute)._1.asInstanceOf[R]
    )

}

