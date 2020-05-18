package tw.idv.idiotech.ghostspeak.agent.knowledge

case class GraphNode(name: String, actions: List[Action], delay: Int) {
  override def toString: String = name
}
