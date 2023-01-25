name := "ClusterCRDTTest"

version := "0.1"

scalaVersion := "2.13.10"

val AkkaVersion = "2.7.0"
val logback = "1.4.5"
val scalaLogging = "3.9.5"
val scalaTestVersion = "3.2.15"

val logging = Vector(
  "ch.qos.logback" % "logback-classic" % logback,
  "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging
)

val testDeps = Vector(
  "org.scalatest" %% "scalatest" % scalaTestVersion,
  "org.scalatest" %% "scalatest-propspec" % scalaTestVersion,
  "org.scalatest" %% "scalatest-wordspec" % scalaTestVersion
).map(_ % Test)

libraryDependencies ++= Vector(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
  "com.typesafe.akka" %% "akka-cluster" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion
) ++ logging ++ testDeps