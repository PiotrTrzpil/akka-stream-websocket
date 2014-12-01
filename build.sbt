name := "akka-stream-websocket"

organization  := "pt"

version       := "0.1"

scalaVersion  := "2.11.4"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Spray repository" at "http://repo.spray.io/"
)

libraryDependencies ++= {
  val akkaV = "2.3.7"
  val sprayV = "1.2.0"
  Seq(
//  "org.java-websocket"  %   "Java-WebSocket" % "1.3.1",
     "com.typesafe.akka"  %% "akka-actor"       % akkaV,
     "com.typesafe.akka"  %% "akka-slf4j"       % akkaV,
     "com.typesafe.akka"  %% "akka-remote"      % akkaV,
     "com.typesafe.akka"  %% "akka-contrib"     % akkaV,
     "com.typesafe.akka" %% "akka-stream-experimental" % "0.11",
     "io.spray"           %% "spray-can"        % "1.3.1",
     "io.spray"           %% "spray-routing"    % "1.3.1",
     "io.spray"           %% "spray-json"       % "1.2.6",
     "org.json4s"         %% "json4s-native"    % "3.2.10",
     "io.dropwizard.metrics" % "metrics-core"   % "3.1.0",
     "ch.qos.logback"        % "logback-classic"% "1.1.2",
    "com.typesafe.akka"   %%  "akka-testkit"   % akkaV   % "test",
   "org.scalatest" %% "scalatest" % "2.2.1" % "test",
     "org.eclipse.jetty" % "jetty-websocket" % "8.1.16.v20140903"
  )
}

net.virtualvoid.sbt.graph.Plugin.graphSettings

seq(Revolver.settings: _*)
