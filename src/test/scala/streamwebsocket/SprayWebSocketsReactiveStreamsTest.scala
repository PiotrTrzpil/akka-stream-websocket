package streamwebsocket

import akka.actor._
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.actor.{WatermarkRequestStrategy, ActorSubscriberMessage, ActorSubscriber, ActorPublisher}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{FlatSpecLike, Matchers}
import streamwebsocket.WebSocketMessage._

import scala.concurrent.Future
import scala.concurrent.duration._
import streamwebsocket.ReactiveServer.{ResourceSubscription, SubscribeForResource}
import akka.io.IO
import spray.can.{websocket, Http}
import spray.can.server.UHttp
import streamwebsocket.SimpleServer.{WebSocketWorker, Push, WebSocketServer}
import spray.can.websocket.frame.{Frame, TextFrame}
import spray.http.{HttpHeaders, HttpMethods, HttpRequest}
import spray.can.websocket.Send
import scala.collection.mutable
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import spray.http.HttpRequest
import spray.can.websocket.Send
import akka.stream.actor.ActorPublisherMessage.Request
import org.reactivestreams.{Subscriber, Publisher}
import streamwebsocket.Websocket.{Connection, Bound}

class SprayWebSocketsReactiveStreamsTest extends TestKit(ActorSystem("Websockets"))
         with FlatSpecLike with Matchers{
   implicit val materializer = FlowMaterializer()
   implicit val exec = system.dispatcher
   implicit val timeout = Timeout(3.seconds)

   "The websocket" should "do" in {
      val probe = TestProbe()


      def runClient() = {
         val client = system.actorOf(WebSocketClient.props(), "websocket-client")
         (client ? Websocket.Connect("localhost", 8080, "/")).onSuccess {
            case Websocket.Connection(inbound, outbound) =>
               println("just got the Connection")
               Source(inbound).foreach { case TextFrame(text) =>
                  val str = text.utf8String
                  println(s"client received: $str")
                  probe.ref ! s"client received: $str"
                  TextFrame("server message")
               }
               Source(200 milli, 200 milli, () => TextFrame("client message"))
                 .runWith(Sink(outbound))
         }
      }


      val server = system.actorOf(WebSocketServer.props(), "websocket")

      (server ? Websocket.Bind("localhost", 8080)).onSuccess {
         case Bound(addr, connections) =>
            runClient()
            Source(connections).foreach { case Connection(inbound, outbound) =>
               println("just got the register")
               Source(inbound).map { case TextFrame(text) =>
                  val str = text.utf8String
                  println(s"server received: $str")
                  probe.ref ! s"server received: $str"
                  TextFrame("server message")
               }.runWith(Sink(outbound))
            }
      }

      try {
         probe.expectMsg("server received: client message")
         probe.expectMsg("client received: server message")
      } finally {
         TestKit.shutdownActorSystem(system)
      }
   }
}