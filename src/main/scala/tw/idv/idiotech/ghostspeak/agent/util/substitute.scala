package tw.idv.idiotech.ghostspeak.agent.util

import shapeless._
import shapeless.ops.hlist.RightFolder
import tw.idv.idiotech.ghostspeak.agent.util.substitute.SubstPair
import shapeless.{Coproduct, :+:, CNil, Inl, Inr}

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

  def apply[P <: Product, R <: HList, B <: HList](p: P, original: String, updated: String)(
    implicit aux: Generic.Aux[P, R],
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

sealed trait Action
case class Speak(actor: String, receiver: String, content: String) extends Action
case class Hit(actor: String, receiver: String) extends Action

trait Subber[A] {
  def subb(a: A, variable: String, instance: String): A
}

object Subber {
  def apply[A](implicit substitute: Subber[A]): Subber[A] = substitute
  implicit class SubstituteOps[A: Subber](val a: A) {
    def sub(variable: String, instance: String) = Subber[A].subb(a, variable, instance)
  }
  implicit val cnilEncoder: Subber[CNil] = (_: CNil, _: String, _: String) => throw new Exception("Inconceivable!")
  implicit def basicSubs[P <: Product, R <: HList, B <: HList](
    implicit aux: Generic.Aux[P, R],
    l: RightFolder.Aux[
      R,
      (HNil, SubstPair),
      substitute.type,
      (B, SubstPair)
    ]
  ): Subber[P] = (a: P, variable: String, instance: String) => substitute(a, variable, instance)

  def createSubber[A](func: (A, String, String) => A) = new Subber[A] {
    override def subb(a: A, variable: String, instance: String): A = func(a, variable, instance)
  }

  implicit def cosubs[H, T <: Coproduct](implicit
    hSubber: Subber[H],
    tSubber: Subber[T]
  ): Subber[H :+: T] = createSubber[H :+: T](

    (a, o, r) => a match {
      case Inl(head) => Inl[H, T](hSubber.subb(head, o, r))
      case Inr(tail) => Inr[H, T](tSubber.subb(tail, o, r))
    }
  )

  implicit val genAction = Generic[Action]
  implicit def generalSubs[A, C <: Coproduct](
     implicit generic: Generic.Aux[A, C],
     subs: Lazy[Subber[C]]
  ): Subber[A] = {
    createSubber[A](
      (a, o, r) =>  generic.from(subs.value.subb(generic.to(a), o, r))
    )
  }

}
