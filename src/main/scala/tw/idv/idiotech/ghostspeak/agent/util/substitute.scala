package tw.idv.idiotech.ghostspeak.agent.util

import shapeless._
import shapeless.ops.hlist.RightFolder
import shapeless.{ :+:, CNil, Coproduct, Inl, Inr }

object substitute extends Poly2 {
  case class SubstPair(oldString: String, newString: String) {
    def work(s: String): String = if (s == oldString) newString else s
  }
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

  type Folder[R <: HList, B <: HList] = RightFolder.Aux[
    R,
    (HNil, SubstPair),
    substitute.type,
    (B, SubstPair)
  ]

  def apply[P <: Product, R <: HList, B <: HList](p: P, original: String, updated: String)(
    implicit aux: Generic.Aux[P, R],
    l: Folder[R, B]
  ): P =
    aux.from(
      aux.to(p).foldRight((HNil: HNil, SubstPair(original, updated)))(substitute)._1.asInstanceOf[R]
    )
}

trait Instantiation[A] {
  def instantiate(a: A, variable: String, instance: String): A
}

object Instantiation {
  def apply[A](implicit instantiation: Instantiation[A]): Instantiation[A] = instantiation

  implicit class InstantiationOps[A: Instantiation](val a: A) {
    def replace(variable: String, instance: String): A = Instantiation[A].instantiate(a, variable, instance)
  }
  implicit val cnilEncoder: Instantiation[CNil] = (_: CNil, _: String, _: String) =>
    throw new Exception("Inconceivable!")

  implicit def instantiateProduct[P <: Product, R <: HList, B <: HList](
    implicit aux: Generic.Aux[P, R],
    l: substitute.Folder[R, B]
  ): Instantiation[P] = (a: P, variable: String, instance: String) => substitute(a, variable, instance)

  private def create[A](func: (A, String, String) => A) = new Instantiation[A] {
    override def instantiate(a: A, variable: String, instance: String): A = func(a, variable, instance)
  }

  implicit def instantiateCoproduct[H, T <: Coproduct](
                                                        implicit
                                                        hInst: Instantiation[H],
                                                        tInst: Instantiation[T]
  ): Instantiation[H :+: T] = create[H :+: T](
    (a, o, r) =>
      a match {
        case Inl(head) => Inl[H, T](hInst.instantiate(head, o, r))
        case Inr(tail) => Inr[H, T](tInst.instantiate(tail, o, r))
      }
  )

  implicit def generalSubs[A, C <: Coproduct](
    implicit generic: Generic.Aux[A, C],
    subs: Lazy[Instantiation[C]]
  ): Instantiation[A] =
    create[A](
      (a, o, r) => generic.from(subs.value.instantiate(generic.to(a), o, r))
    )

}
