package streamwebsocket.example

import streamwebsocket.{WebSocketClient, WebSocketMessage, WebSocketServer}
import streamwebsocket.WebSocketMessage.{Connection, Bound}
import akka.stream.scaladsl.{Sink, Source}
import spray.can.websocket.frame.TextFrame
import akka.stream.{ActorFlowMaterializer, FlowMaterializer}
import akka.util.Timeout
import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.pattern._

class Example extends App {

   implicit val system = ActorSystem()
   implicit val materializer = ActorFlowMaterializer()
   implicit val exec = system.dispatcher
   implicit val timeout = Timeout(3.seconds)

   val server = system.actorOf(WebSocketServer.props(), "websocket-server")
   (server ? WebSocketMessage.Bind("localhost", 8080)).map {
      case Bound(addr, connections) =>
         Source(connections).runForeach {
            case WebSocketMessage.Connection(inbound, outbound) =>
               Source(inbound).map { case TextFrame(text) =>
                  TextFrame(text.utf8String.toUpperCase)
               }.runWith(Sink(outbound))
         }
   }.onSuccess { case _ =>

      val client = system.actorOf(WebSocketClient.props(), "websocket-client")
      (client ? WebSocketMessage.Connect("localhost", 8080, "/")).map {
         case WebSocketMessage.Connection(inbound, outbound) =>
            Sink(outbound).runWith(
               Source(200 milli, 200 milli, TextFrame("hi")))
            Source(inbound).runForeach { case TextFrame(text) =>
               println(text.utf8String)
            }

      }
   }
}
