package asp

import scala.concurrent.ExecutionContextExecutor
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.remote.transport.ActorTransportAdapter.AskTimeout

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("asp_crdt")
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  val log = system.log
  val initialActor = system.actorOf(Props[MessageReceiver])

  initialActor.?(MessageReceiver.DataToStore).mapTo[MessageReceiver.StoreResult]
    .map(storedAt => log.info("message stored at", storedAt.timestamp))

  log.warning("Press Enter to shutdown")
  scala.io.StdIn.readLine()
  system.classicSystem.terminate()
    .andThen {
      case _ => log.info("asp_crdt process terminated")
    }
}
