package asp

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import asp.Auditor.Audit
import asp.PowerGrid.GridState
import asp.PowerGridExt.GridStateOps
import com.github.tototoshi.csv.CSVWriter

class Auditor(powerGridId: String) extends Actor {
  override def receive: Receive = {
    case Audit(nominalPower, actualPower, time) =>
      writeAudit(nominalPower, actualPower, time)
  }

  private def writeAudit(nominalPower: Int, actualPower: Int, time: Long): Unit = {
    val writer = CSVWriter.open(s"$powerGridId-power-grid.csv", append = true)
    writer.writeRow(List(nominalPower, actualPower, time))
    writer.close()
  }
}

object Auditor {

  case class Audit(nominalPower: Int, actualPower: Int, time: Long)

  def apply(id: String)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(classOf[Auditor], id))
}
