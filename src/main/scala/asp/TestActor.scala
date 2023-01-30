package asp

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.ddata.{DistributedData, LWWMapKey, SelfUniqueAddress}
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import asp.PowerGrid.GridShape

class TestActor extends Actor {
  private val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress

  private val DataKey = LWWMapKey[String, GridShape]("power-grid")

  replicator ! Subscribe(DataKey, self)

  override def receive: Receive = {
    case c@Changed(DataKey) =>
      val data = c.get(DataKey)
      println("Current elements: {}", data)
  }
}

object TestActor {

  def start(implicit system: ActorSystem): ActorRef = system.actorOf(Props(classOf[TestActor]))
}
