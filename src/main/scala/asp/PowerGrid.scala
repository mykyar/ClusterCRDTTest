package asp

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.ddata.Replicator.{Changed, Subscribe, Update, UpdateSuccess, WriteMajority}
import akka.cluster.ddata.{DistributedData, LWWMap, LWWMapKey, SelfUniqueAddress}
import asp.Auditor.Audit
import asp.PowerGrid.{GridShape, GridState, OfferPower, OfferPowerAck, UnitId}
import asp.PowerGridExt.GridStateOps
import asp.PowerUnit.UnitState

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

class PowerGrid(id: String, nominalPower: Int, minors: Seq[UnitId], auditor: ActorRef) extends Actor with ActorLogging {

  private val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress

  private val EntirePowerGridKey = LWWMapKey[ActorRef, GridShape]("entire-power-grid")

  replicator ! Subscribe(EntirePowerGridKey, self)

  override def preStart(): Unit = {
    minors.foreach(unitId => context.actorOf(Props(classOf[PowerUnit], unitId)))
  }
  override def receive: Receive = accept(GridState(id, nominalPower, Map.empty))

  private def accept(state: GridState): Receive = {
    case unitState: UnitState =>
      log.debug(s"[$id] Received an update from a power unit $unitState")

      val newState = state.copy(units = state.units + (unitState.id -> unitState))

      log.info(s"[$id] new state of power grid: actual: ${newState.actualPower}, nominal: ${newState.nominalPower}, delta: ${newState.deltaPower}")

      auditor ! Audit(state.nominalPower, state.actualPower, System.currentTimeMillis())

      val writeMajority = WriteMajority(timeout = 5.seconds)

      replicator ! Update(EntirePowerGridKey, LWWMap.empty[ActorRef, GridShape], writeMajority
        )(_ :+ (self -> GridShape(state.id, newState.nominalPower - newState.actualPower)))

      context.become(accept(newState), discardOld = true)

    case UpdateSuccess(key, _) =>
      log.debug(s"[UpdateSuccess] Updated: $key, id: $id")
    case entirePowerGrid@Changed(EntirePowerGridKey) =>
      log.info(s"[EntirePowerGrid State Changed] : ${entirePowerGrid.get(EntirePowerGridKey)}")
      val notUsedPower = state.deltaPower
      if (notUsedPower > 0){

        val globalState = entirePowerGrid.get(EntirePowerGridKey)

        allocateExtraPower(globalState.entries.filter(x => x._1 != self).toList, notUsedPower)
      } else {
        log.info("Not enough power to lend")
      }
    case OfferPower(additionalPower) =>
      log.info(s"[OfferPower] Received a offer with power: $additionalPower")

      if (state.deltaPower < 0){
        context.become(accept(state.copy(nominalPower = state.nominalPower + additionalPower)), discardOld = true)
        sender() ! OfferPowerAck(additionalPower)
      } else {
        sender() ! OfferPowerAck(0)
      }
    case OfferPowerAck(borrowPower) =>
      log.info(s"[OfferPowerAck] Nominal power of the current grid will be decreased by $borrowPower")
      context.become(accept(state.copy(nominalPower = state.nominalPower - borrowPower)), discardOld = true)
  }

  @tailrec
  private def allocateExtraPower(items: List[(ActorRef, GridShape)], extraPower: Int): Int = {
    if (items.nonEmpty){
      val item = items.head

      val leftoverPower = if (item._2.deltaPower < 0){
        val remainsPower = extraPower + item._2.deltaPower

        val borrowedPower = if (remainsPower < 0) extraPower else remainsPower

        item._1 ! OfferPower(borrowedPower)

        if (remainsPower < 0) 0 else remainsPower
      } else {
        extraPower
      }
      allocateExtraPower(items.tail, leftoverPower)
    } else {
      extraPower
    }
  }
}

object PowerGrid {

  type UnitId = String

  case class GridState(id: String, nominalPower: Int, units: Map[UnitId, UnitState])
  case class GridShape(id: String, deltaPower: Int)
  case class OfferPower(lendPower: Int)
  case class OfferPowerAck(borrowPower: Int)

  def start(id: String, gridNominalPower: Int, units: Seq[UnitId], auditor: ActorRef)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(classOf[PowerGrid], id, gridNominalPower, units, auditor), id)
}

object PowerGridExt {
  implicit class GridStateOps(s: GridState) {
    def actualPower: Int = s.units.values.map(_.actualPower).sum
    def deltaPower: Int = s.nominalPower - actualPower
  }
}
