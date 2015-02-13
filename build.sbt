name := "akka-stream-websocket"

organization  := "pt"

version       := "0.1"

scalaVersion  := "2.11.5"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Spray repository" at "http://repo.spray.io/"
)

libraryDependencies ++= {
  val akkaV = "2.3.7"
  Seq(
     "com.typesafe.akka"  %% "akka-actor"       % akkaV,
     "com.typesafe.akka"  %% "akka-slf4j"       % akkaV,
     "com.typesafe.akka"  %% "akka-remote"      % akkaV,
     "com.typesafe.akka"  %% "akka-contrib"     % akkaV,
     "com.typesafe.akka"  %% "akka-stream-experimental" % "1.0-M3",
     "com.wandoulabs.akka"%% "spray-websocket"  % "0.1.3",
     "io.spray"           %% "spray-can"        % "1.3.1",
     "io.spray"           %% "spray-routing"    % "1.3.1",
     "io.spray"           %% "spray-json"       % "1.2.6",
     "ch.qos.logback"      % "logback-classic"  % "1.1.2",
     "com.typesafe.akka"  %%  "akka-testkit"    %  akkaV  % "test",
     "org.scalatest"      %% "scalatest"        % "2.2.1" % "test",
  )
}

net.virtualvoid.sbt.graph.Plugin.graphSettings

seq(Revolver.settings: _*)
