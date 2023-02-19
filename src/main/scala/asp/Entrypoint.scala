package asp

import scala.concurrent.ExecutionContextExecutor
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.MemberRemoved
import com.typesafe.config.ConfigFactory

object Entrypoint extends App {

  val conf = if (args.length == 0) {
    throw new IllegalArgumentException("Please specify config of node")
  } else {
    ConfigFactory.load().getConfig(s"akka.${args(0)}")
  }

  implicit val system: ActorSystem = ActorSystem("power-grid-sms", conf.withFallback(ConfigFactory.load()))
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val cluster = Cluster(system)

  cluster.join(cluster.selfMember.address.copy(
    host = Some(conf.getString(s"akka.cluster-host")),
    port = Some(conf.getInt(s"akka.cluster-port")))
  )

  val powerGrid = PowerGrid.start(s"${args(0)}", 25, Seq("s1", "s2"), Auditor(s"${args(0)}"), conf.getInt("region-id"))

  cluster.subscribe(powerGrid, classOf[MemberRemoved])

  system.log.warning("Press Enter to shutdown")
  scala.io.StdIn.readLine()
  system.classicSystem.terminate()
    .andThen {
      case _ => system.log.info("power-grid-sms process terminated")
    }
}
