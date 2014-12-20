package streamwebsocket

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Source
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{FlatSpecLike, Matchers}
import streamwebsocket.WebSocketMessage._

import scala.concurrent.Future
import scala.concurrent.duration._
import streamwebsocket.ReactiveServer.{ResourceSubscription, SubscribeForResource}
import java.io.{BufferedReader, InputStreamReader, IOException}
import scala.tools.nsc.interpreter.session
import spray.http.CacheDirectives.public
import scala.tools.nsc.interpreter
import javax.websocket.{MessageHandler, Session, Endpoint, EndpointConfig}
import org.glassfish.tyrus.server.Server


class ReactiveWebsocketTest extends TestKit(ActorSystem("Websockets"))
         with FlatSpecLike with Matchers {
   implicit val materializer = FlowMaterializer()
   implicit val exec = system.dispatcher
   implicit val timeout = Timeout(3.seconds)

   "The websocket" should "exchange basic messages between client and server" in {

      val probe = TestProbe()
      val server = system.actorOf(Props(classOf[ReactiveServer], 8080))

      (server ? SubscribeForResource("/somepath"))
        .onSuccess { case ResourceSubscription(routeSource) =>
         routeSource.foreach(connection => {
            connection ! WebSocketSend("server message")
            Source(ActorPublisher[String](connection))
              .foreach(str => {
               println(s"server received: $str")
               probe.ref ! s"server received: $str"

            })
         })
      }

      val client = system.actorOf(Props(classOf[WebSocketActorClient], "ws://localhost:8080/somepath"))

      Source(ActorPublisher(client))
        .foreach { any: String =>
         println(s"client received: $any")
         probe.ref ! s"client received: $any"
      }

      client ! WebSocketSend("client message")

      Thread.sleep(2000)
      //   probe.expectMsg("server received: client message")
      //  probe.expectMsg("client received: server message")

   }
   "The websocket" should "exchange basic messages between client and server3" in {

      val serverProbe = TestProbe()
      val clientProbe = TestProbe()
      val server = system.actorOf(Props(classOf[ReactiveServer], 8080))

      (server ? SubscribeForResource("/somepath"))
        .onSuccess { case ResourceSubscription(routeSource) =>
         routeSource.foreach(connection => {

            (connection ? SubscribeOpen)
              .mapTo[Future[ServerOpen]]
              .map(fut => fut.onSuccess { case open =>
               serverProbe.ref ! "open"
            })
            (connection ? SubscribeClose)
              .mapTo[Future[Close]]
              .map(fut => fut.onSuccess { case close =>
               serverProbe.ref ! "close"
            })

            Source(ActorPublisher[String](connection))
              .foreach(str => {
               connection ! WebSocketSend("server message")
               serverProbe.ref ! s"server received: $str"
            })
         })
      }

      val client = system.actorOf(Props(classOf[WebSocketActorClient], "ws://localhost:8080/somepath"))

      (client ? SubscribeOpen)
        .mapTo[Future[ClientOpen]]
        .map(fut => fut.onSuccess { case open => clientProbe.ref ! "open"})
      (client ? SubscribeClose)
        .mapTo[Future[Close]]
        .map(fut => fut.onSuccess { case close => clientProbe.ref ! "close"})

      Source(ActorPublisher(client))
        .foreach { any: String =>
         clientProbe.ref ! s"client received: $any"
      }

      client ! WebSocketSend("client message")

      Thread.sleep(500.millis.toMillis)

      client ! WebSocketClose

      clientProbe.expectMsg("open")
      clientProbe.expectMsg("client received: server message")
      clientProbe.expectMsg("close")
      serverProbe.expectMsg("open")
      serverProbe.expectMsg("server received: client message")
      serverProbe.expectMsg("close")

   }

   "The websocket" should "exchange basic messages between client and server4" in {

      val server = new Server("localhost", 8025, "/websocket", null, classOf[EchoEndpointProgrammatic])

      try {
         server.start()
         val reader = new BufferedReader(new InputStreamReader(System.in))
         System.out.print("Please press a key to stop the server.")
         reader.readLine();
      } catch (Exception e) {
         e.printStackTrace()
      } finally {
         server.stop()
      }
   }

   class EchoEndpointProgrammatic extends Endpoint {
      def onOpen(session: Session, config: EndpointConfig) {
         session.addMessageHandler(new MessageHandler.Whole[String]() {
            def onMessage(message: String) {
               session.getBasicRemote.sendText(message)
            }
         })
      }
   }

}