package tw.idv.idiotech.ghostspeak.agent

import cats.effect.IO

trait Query

class Memory {
  def remember(event: Message): IO[Memory] = ???
  def query(query: Query): IO[List[Message]] = ???
}
