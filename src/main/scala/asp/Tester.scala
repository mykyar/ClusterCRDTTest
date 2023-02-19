package asp

import asp.util.Utilities

import scala.util.Random

object Tester extends App {
  val seq = Random.shuffle(Seq(1, 2, 3, 4, 5, 6, 7, 8, 9))

  println(seq)

  val ranked = seq.sortWith(Utilities.compareByRankClosest(4))

  println(ranked)

  val revertedRanked = seq.sortWith(Utilities.compareByRankEndmost(4))

  println(revertedRanked)

}
