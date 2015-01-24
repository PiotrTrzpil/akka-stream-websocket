package streamwebsocket

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.actor.ActorPublisher
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
import streamwebsocket.SimpleServer.WebSocketServer
import spray.can.websocket.frame.TextFrame
import spray.http.{HttpHeaders, HttpMethods, HttpRequest}
import spray.can.websocket.Send


class SprayWebSocketsReactiveStreamsTest extends TestKit(ActorSystem("Websockets"))
         with FlatSpecLike with Matchers{
   implicit val materializer = FlowMaterializer()
   implicit val exec = system.dispatcher
   implicit val timeout = Timeout(3.seconds)

   class Client(probe:TestProbe, val upgradeRequest: HttpRequest) extends websocket.WebSocketClientWorker {
      IO(UHttp) ! Http.Connect("localhost", 8080, false)

      def businessLogic: Receive = {
         case Send(frame) => connection ! frame
         case TextFrame(text) =>
            val str = text.utf8String
            println("client received: "+str)
            probe.ref ! s"client received: "+str

         case _: Http.ConnectionClosed =>
            context.stop(self)
         case x => connection ! x
      }

   }

   "The websocket" should "do" in {
      val probe = TestProbe()

      val server = system.actorOf(WebSocketServer.props(), "websocket")
      (server ? Websocket.Bind("localhost", 8080)).onSuccess {
         case Websocket.Connection(inbound, outbound) =>
            println("just got the register")
            Source(inbound).map { case TextFrame(text) =>
               val str = text.utf8String
               println(s"server received: $str")
               probe.ref ! s"server received: $str"
               TextFrame("server message")
            }.runWith(Sink(outbound))
      }

      val headers = List(
      HttpHeaders.Host("localhost", 8080),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
         HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
         HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
         HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

      val client = system.actorOf(Props(new Client(probe, HttpRequest(HttpMethods.GET, "/", headers))))
      client ! Send(TextFrame("client message"))

      try {
         probe.expectMsg("server received: client message")
         probe.expectMsg("client received: server message")
      } finally {
         TestKit.shutdownActorSystem(system)
      }
   }
}