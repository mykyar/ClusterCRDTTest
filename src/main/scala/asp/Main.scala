package asp

import scala.concurrent.ExecutionContextExecutor
import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory

object Main extends App {

  val conf = if (args.length == 0) {
    throw new IllegalArgumentException("Please specify config of node")
  } else {
    ConfigFactory.load().getConfig(s"akka.${args(0)}")
  }

  implicit val system: ActorSystem = ActorSystem("asp_crdt", conf.withFallback(ConfigFactory.load()))
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val cluster = Cluster(system)

  cluster.join(cluster.selfMember.address.copy(host = Some(conf.getString(s"akka.cluster-host")), port = Some(conf.getInt(s"akka.cluster-port"))))

  val log = system.log

  PowerGrid.start(s"${args(0)}", 25, Seq("s1", "s2"))

//  TestActor.start

  log.warning("Press Enter to shutdown")
  scala.io.StdIn.readLine()
  system.classicSystem.terminate()
    .andThen {
      case _ => log.info("asp_crdt process terminated")
    }
}
