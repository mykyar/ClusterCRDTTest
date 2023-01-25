package asp

import java.time.Instant
import akka.actor.Actor
class MessageReceiver extends Actor {
  override def receive: Receive = {
    case MessageReceiver.DataToStore =>
      sender() ! MessageReceiver.StoreResult(Instant.now())
  }
}

object MessageReceiver {
  object DataToStore
  case class StoreResult(timestamp: Instant)
}
