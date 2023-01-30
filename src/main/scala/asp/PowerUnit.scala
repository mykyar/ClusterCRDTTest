package asp

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Timers}

import scala.concurrent.duration._
import asp.PowerUnit.{NotifyGridTimer, StateUpdate, UnitState}

import scala.concurrent.ExecutionContext
import scala.util.Random

class PowerUnit(unitId: String) extends Actor with Timers {
  implicit val executionContext: ExecutionContext = context.dispatcher

  timers.startTimerAtFixedRate("powerGridNotifier", NotifyGridTimer, 1.second)
  context.system.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(() => context.self ! StateUpdate())

  override def receive: Receive = accept(UnitState(id = unitId, actualPower = 0))

  private def accept(state: UnitState): Receive = {
    case NotifyGridTimer =>
      context.parent ! state
    case StateUpdate(updatedActualPower) =>
      context.become(accept(state.copy(actualPower = updatedActualPower)), discardOld = true)
  }
}

object PowerUnit {
  case class UnitState(id: String, actualPower: Int)
  case class StateUpdate(actualPower: Int = Random.nextInt(5))
  case object NotifyGridTimer

  def apply(id: String)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(classOf[PowerUnit], id))
}
