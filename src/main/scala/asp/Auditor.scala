package asp

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.ddata.{DistributedData, LWWMapKey, SelfUniqueAddress}
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import asp.Auditor.Audit
import asp.PowerGrid.{GridShape, GridState}
import asp.PowerGridExt.GridStateOps
import com.github.tototoshi.csv.CSVWriter

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import scala.collection.immutable.List

class Auditor(powerGridId: String) extends Actor {
  private val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress
  private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss.SSS").withZone(ZoneId.systemDefault)

  private val EntirePowerGridKey = LWWMapKey[ActorRef, GridShape]("entire-power-grid")

  replicator ! Subscribe(EntirePowerGridKey, self)

  override def receive: Receive = {
    case data: Audit =>
      writeAudit(data.currentGrid, data.restGrids, data.time)
  }

  private def writeAudit(currentGrid: GridState, restGrids: List[GridShape], time: Long): Unit = {
    val writer = CSVWriter.open(s"$powerGridId-power-grid.csv", append = true)
    writer.writeRow(List(s"current: $powerGridId", currentGrid.nominalPower, currentGrid.actualPower, currentGrid.lentPower.values.sum, currentGrid.borrowedPower.values.sum, dateFormat.format(Instant.ofEpochMilli(time))))
    restGrids.foreach { grid =>
      writer.writeRow(List(grid.id, grid.deltaPower, dateFormat.format(Instant.ofEpochMilli(grid.timestamp))))
    }
    writer.writeRow(List("-".repeat(25)))
    writer.close()
  }
}

object Auditor {

  case class Audit(currentGrid: GridState, restGrids: List[GridShape], time: Long)

  def apply(id: String)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(classOf[Auditor], id))
}
