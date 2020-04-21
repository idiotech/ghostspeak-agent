package tw.idv.idiotech.ghostspeak.agent

import cats.effect.IO

trait Query

trait Memory {
  def remember(belief: Belief): IO[Memory]
  def query(query: Query): IO[List[Belief]]
}
