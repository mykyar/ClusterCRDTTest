package asp

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ddata.Replicator.{Changed, Subscribe, Update, UpdateSuccess, WriteMajority}
import akka.cluster.ddata.typed.scaladsl.Replicator.WriteLocal
import akka.cluster.ddata.{DistributedData, LWWMap, LWWMapKey, SelfUniqueAddress}
import asp.Auditor.Audit
import asp.PowerGrid.{BringBackPower, GridShape, GridState, OfferPower, OfferPowerAck, UnitId}
import asp.PowerGridExt.GridStateOps
import asp.PowerUnit.UnitState
import asp.util.Utilities

import scala.annotation.tailrec


class PowerGrid(id: String, nominalPower: Int, minors: Seq[UnitId], auditor: ActorRef, regionId: Int) extends Actor with ActorLogging {

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

      val notUsedPower = newState.deltaPower
      val updatedSate = if (notUsedPower > 0){
        val payDebt = bringBackExtraPower(notUsedPower, newState.borrowedPower.toList/*.sortWith(sortByEndmostRegion)*/)(Nil)
        payDebt.foreach { case (owner, debt) => owner ! BringBackPower(debt) }
        newState.copy(
          nominalPower = newState.nominalPower - payDebt.map(_._2).sum,
          borrowedPower = newState.borrowedPower -- payDebt.map(_._1)
        )
      } else {
        newState
      }

      changeEntirePowerGridSate(updatedSate)

      context.become(accept(updatedSate), discardOld = true)

    case UpdateSuccess(key, _) =>
      log.debug(s"[$id][UpdateSuccess] Updated: $key, id: $id")

    case entirePowerGrid@Changed(EntirePowerGridKey) =>
      log.info(s"[$id][EntirePowerGrid State Changed] : ${entirePowerGrid.get(EntirePowerGridKey)}")
      val globalState = entirePowerGrid.get(EntirePowerGridKey)

      val notUsedPower = state.deltaPower
      if (notUsedPower > 0){
        allocateExtraPower(globalState.entries.filter(_._1 != self).toList.sortWith(sortByClosestRegion), notUsedPower)
      } else {
        log.info(s"[$id] Not enough power to lend")
      }
      val otherNodes = globalState.entries.filter(_._1 != self).values.toList
      auditor ! Audit(state, otherNodes, System.currentTimeMillis())

    case OfferPower(borrowedPower) =>
      log.info(s"[OfferPower] Received an offer with power: $borrowedPower")

      if (state.deltaPower < 0){
        context.become(
          accept(
            state.copy(
              nominalPower = state.nominalPower + borrowedPower,
              borrowedPower = state.borrowedPower + (sender() -> (state.borrowedPower.getOrElse(sender(), 0) + borrowedPower)))
          ), discardOld = true)
        sender() ! OfferPowerAck(borrowedPower)
      } else {
        sender() ! OfferPowerAck(0)
      }

    case OfferPowerAck(lentPower) =>
      if (lentPower > 0){
        log.info(s"[OfferPowerAck] Nominal power of the current grid will be decreased by $lentPower")
        context.become(
          accept(
            state.copy(
              nominalPower = state.nominalPower - lentPower,
              lentPower = state.lentPower + (sender() -> (state.lentPower.getOrElse(sender(), 0) + lentPower)))
          ), discardOld = true)
      }

    case MemberRemoved(member, _) =>
      val failureAddress = member.uniqueAddress.address
      val takeBackData = state.borrowedPower.filter(_._1.path.address == failureAddress)
      val giveBackData = state.lentPower.filter(_._1.path.address == failureAddress)

      val updatedState = state.copy(
        nominalPower = state.nominalPower - takeBackData.values.sum + giveBackData.values.sum,
        lentPower = state.lentPower -- takeBackData.keys,
        borrowedPower = state.borrowedPower -- giveBackData.keys,
      )
      changeEntirePowerGridSate(updatedState)
      context.become(accept(updatedState), discardOld = true)

    case BringBackPower(power) =>
      val updatedState = state.copy(
        nominalPower = state.nominalPower + power,
        lentPower = state.lentPower - sender()
      )
      changeEntirePowerGridSate(updatedState)
      context.become(accept(updatedState), discardOld = true)
  }

  private def changeEntirePowerGridSate(state: GridState): Unit = {
    replicator ! Update(EntirePowerGridKey, LWWMap.empty[ActorRef, GridShape], WriteLocal
    )(_ :+ (self -> GridShape(state.id, state.deltaPower, regionId, System.currentTimeMillis())))
  }

  private val sortByClosestRegion: ((ActorRef, GridShape), (ActorRef, GridShape)) => Boolean = (t1, t2) =>
    Utilities.compareByRankClosest(regionId)(t1._2.regionId, t2._2.regionId)

/*
  private val sortByEndmostRegion: ((ActorRef, Int), (ActorRef, Int)) => Boolean = (t1, t2) =>
    Utilities.compareByRankEndmost(regionId)(t1._2.regionId, t2._2.regionId)
*/

  @tailrec
  private def allocateExtraPower(items: List[(ActorRef, GridShape)], extraPower: Int): Int = {
    if (items.nonEmpty){
      val item = items.head

      val leftoverPower = if (item._2.deltaPower < 0){
        val remainsPower = extraPower + item._2.deltaPower

        val lentPower = if (remainsPower < 0) extraPower else remainsPower

        item._1 ! OfferPower(lentPower)

        if (remainsPower < 0) 0 else remainsPower
      } else {
        extraPower
      }
      allocateExtraPower(items.tail, leftoverPower)
    } else {
      extraPower
    }
  }

  @tailrec
  private def bringBackExtraPower(notUsedPower: Int, borrowedPower: List[(ActorRef, Int)])(acc: List[(ActorRef, Int)]): List[(ActorRef, Int)] = {
    if (borrowedPower.nonEmpty && notUsedPower > 0) {
      val (owner, power) = borrowedPower.head
      if (notUsedPower >= power) {
        bringBackExtraPower(notUsedPower - power, borrowedPower.tail)(acc :+ (owner -> power))
      } else {
        bringBackExtraPower(notUsedPower, borrowedPower.tail)(acc)
      }
    } else {
      acc
    }
  }
}

object PowerGrid {

  type UnitId = String

  case class GridState(id: String, nominalPower: Int, units: Map[UnitId, UnitState] = Map.empty,
                       lentPower: Map[ActorRef, Int] = Map.empty, borrowedPower: Map[ActorRef, Int] = Map.empty)
  case class GridShape(id: UnitId, deltaPower: Int, regionId: Int, timestamp: Long)
  case class OfferPower(lendPower: Int)
  case class OfferPowerAck(borrowPower: Int)
  case class BringBackPower(power: Int)

  def start(id: String, gridNominalPower: Int, units: Seq[UnitId], auditor: ActorRef, regionId: Int)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(classOf[PowerGrid], id, gridNominalPower, units, auditor, regionId), id)
}

object PowerGridExt {
  implicit class GridStateOps(s: GridState) {
    def actualPower: Int = s.units.values.map(_.actualPower).sum
    def deltaPower: Int = s.nominalPower - borrowedPower - lentPower - actualPower
    def borrowedPower: Int = s.borrowedPower.values.sum
    def lentPower: Int = s.lentPower.values.sum
  }
}
