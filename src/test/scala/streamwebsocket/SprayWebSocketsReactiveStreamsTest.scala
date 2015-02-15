package streamwebsocket

import akka.actor._
import akka.pattern.ask
import akka.stream.{ActorFlowMaterializer, FlowMaterializer}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import streamwebsocket.WebSocketMessage.{Connection, Bound}
import scala.concurrent.Await
import java.util.concurrent.TimeUnit

class SprayWebSocketsReactiveStreamsTest extends TestKit(ActorSystem("WebSockets"))
         with FlatSpecLike  with BeforeAndAfter with Matchers {
   implicit val materializer = ActorFlowMaterializer()
   implicit val exec = system.dispatcher
   val timeoutDuration: FiniteDuration = 3.seconds
   implicit val timeout = Timeout(timeoutDuration.toSeconds, TimeUnit.SECONDS)
   val port = 12345

   after {
      TestKit.shutdownActorSystem(system)
   }

   "The WebSocket client" should "send a message to server and receive it back" in {
      try {
         val probe = TestProbe()
         //
         //      val server = system.actorOf(WebSocketServer.props(), "websocket-server")
         //
         //      (server ? WebSocketMessage.Bind("localhost", port)).onSuccess {
         //         case Bound(addr, connections) =>
         //
         //            Source(connections).runForeach { case Connection(inbound, outbound) =>
         //               Source(inbound).map { case TextFrame(text) =>
         //                  val str = text.utf8String
         //                  println(s"server received: $str")
         //                  probe.ref ! s"server received: $str"
         //                  TextFrame("server message")
         //               }.runWith(Sink(outbound))
         //            }

         runServer(probe)

         val clientActor = connectClient(probe)

         probe.expectMsg("server received: client message")
         probe.expectMsg("client received: server message")

         clientActor ! PoisonPill

         val clientActor2 = connectClient(probe)

         probe.expectMsg("server received: client message")
         probe.expectMsg("client received: server message")

         clientActor2 ! PoisonPill

         //      }

         //      try {
         //
         //
      } finally {
         TestKit.shutdownActorSystem(system)
      }
   }
   def runServer(probe:TestProbe) = {
      val server = system.actorOf(WebSocketServer.props(), "websocket-server")
      val Bound(addr, connections) =
         Await.result(server ? WebSocketMessage.Bind("localhost", port), timeoutDuration)

      Source(connections).runForeach { case Connection(inbound, outbound) =>
         Source(inbound).map { case TextFrame(text) =>
            val str = text.utf8String
            println(s"server received: $str")
            probe.ref ! s"server received: $str"
            TextFrame("server message")
         }.runWith(Sink(outbound))
      }

   }
   def connectClient(probe:TestProbe) = {
      val client = system.actorOf(WebSocketClient.props())

      val WebSocketMessage.Connection(inbound, outbound) =
         Await.result((client ? WebSocketMessage.Connect("localhost", port, "/"))
           .mapTo[WebSocketMessage.Connection],timeoutDuration)
      println("just got the Connection")
      Source(inbound).runForeach { case TextFrame(text) =>
         val str = text.utf8String
         println(s"client received: $str")
         probe.ref ! s"client received: $str"
         TextFrame("server message")
      }
      Source(List(TextFrame("client message"))).runWith(Sink(outbound))

      client
   }



}