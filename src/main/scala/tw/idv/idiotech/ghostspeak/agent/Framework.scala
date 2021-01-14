package tw.idv.idiotech.ghostspeak.agent

class Coordinator
class PlannerRegistry
class Deliberator
case class Framework(coordinator: Coordinator, deliberator: Deliberator)

/*

Actor: input / output
Planner: input / output
Deliberator: input / output
ActorRegistry: input / output

  action -> lookup by type -> send to actor
  planner -> action ->

  I see something.
  I am motivated to achieve something.
  According to the goal, I need to do something.
  I have the ability to do that thing.
  After I do that thing, I observe whether the action is successful.
  When action is not successful, I may want to retry.
  I see the effect of the action.
  The effect may motivate me to achieve.
  The effect may trigger a new action.

  Deliberator => input = perception, output = goal
  Planner => input = perception, output = action; (goal can be a perception)
  Multiple planners => for all; push to every planner
  graph planner => internally, at new perception, activate triggers and add new triggers -> as internal state

  Akka persistence
    * 


*/

