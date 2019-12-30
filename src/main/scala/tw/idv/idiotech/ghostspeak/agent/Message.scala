package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.ActorRef
import shapeless._
import Poly._
import enumeratum._
import shapeless.Generic.Aux
import shapeless.PolyDefns.~>
import shapeless.ops.coproduct.Folder
import shapeless.ops.hlist.{ IsHCons, Mapper, RightFolder }

sealed trait Modality extends EnumEntry

object Modality extends Enum[Modality] {

  val values = findValues
  case object Sensed extends Modality
  case object Doing extends Modality
  case object Done extends Modality
  case object Intend extends Modality
}

sealed trait Event

sealed trait Action extends Event

case class Ghostspeak(sender: String, receiver: String) extends Action

object Ghostspeak {
  val speakGen: Aux[Ghostspeak, String :: String :: HNil] = Generic[Ghostspeak]

  trait lpTestfun extends Poly1 {
    implicit def default[A] = at[A](identity)
  }
  object testfun extends lpTestfun

  class Replace(str: String, replacement: String) extends lpTestfun {
    implicit val atString = at[String](s => if (s == str) replacement else s)
  }

  case class Replacement(oldString: String, newString: String) {
    def work(s: String): String = if (s == oldString) newString else s
  }

  object replace extends Poly2 {
    implicit def work[A <: HList, S <: String] = at[S, (Replacement, A)] {
      case (i, (rep, res)) => {
        val res2: A = res
        val it: String = i
        val rep2: Replacement = rep
        val pair: (Replacement, String) = (rep, if (i == rep.oldString) rep.newString else i)
        val ret
          : (Replacement, String) :: A = (rep, if (i == rep.oldString) rep.newString else i) :: res
        ret
      }
    }
//    implicit def workA[A <: HList, Any] = at[Any, (Replacement, A)]  {
//      case (i, (rep, res)) => (rep, i) :: res
//    }
  }

  def genericReplacement[T <: Product: Generic, L <: HList, L2 <: HList](
    str: String,
    replacement: String,
    t: T
  )(
    implicit gen: Generic.Aux[T, L],
    folder: RightFolder.Aux[L, (Replacement, HNil), replace.type, (Replacement, L2)]
  ) = {

    val r = Replacement(str, replacement)
    val hlist = gen.to(t)
    val newList = hlist.foldRight((r, HNil: HNil))(replace)(folder)
    gen.from(newList._2.asInstanceOf[L])
  }

  val gen = Generic[Ghostspeak]

  def test(str: String, replacement: String) = {

    object replace extends lpTestfun {
      implicit val atString = at[String](s => if (s == str) replacement else s)
    }

    val test = Ghostspeak("?a", "me")
    val hlist: String :: String :: HNil = speakGen.to(test)
    val newList: String :: String :: HNil = hlist.map(replace)
    speakGen.from(newList)
  }

}

// sender receiver content

case class Message(sender: ActorRef[Message], modality: String, payload: String)
