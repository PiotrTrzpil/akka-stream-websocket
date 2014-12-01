package streamwebsocket

import java.net.InetSocketAddress

import akka.actor._
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.Source
import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import streamwebsocket.WebSocketMessage._

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.Success
import streamwebsocket.ReactiveServer.{SubscribeForResource, ResourceSubscription, RemoveChild}

object ReactiveServer {
   case class SubscribeForResource(descriptor: String)
   case class ResourceSubscription(source: Source[ActorRef])
   case class RemoveChild(ws: WebSocket)
}


class WebSocketPublisher(ws: WebSocket) extends ActorPublisher[String] with ActorLogging {

   val closing = Promise[WebSocketMessage.Close]()
   val opening = Promise[WebSocketMessage.ServerOpen]()

   val receiveQueue = mutable.Queue[String]()

   implicit val exec = context.system.dispatcher

   override def postStop() = {
      ws.close()
      super.postStop()
   }

   def receive = {
      case message: String =>
         if (totalDemand > 0) {
            onNext(message)
         } else {
            receiveQueue += message
         }

      case SubscribeClose =>
         sender() ! closing.future

      case SubscribeOpen =>
         sender() ! opening.future

      case open: WebSocketMessage.ServerOpen =>
         log.debug(s"Received WebSocketMessage.ServerOpen.")
         opening.complete(Success(open))

      case close: WebSocketMessage.Close =>
         log.debug(s"Received WebSocketMessage.Close.")
         closing.complete(Success(close))
         onComplete()
         context.parent ! RemoveChild(ws)
         self ! PoisonPill

      case WebSocketMessage.Error(cause) =>
         log.debug(s"Got error: " + cause)
         onError(cause)

      case WebSocketSend(message) =>
         log.debug(s"WebSocketSend")
         ws.send(message)

      case WebSocketClose =>
         log.debug(s"Received WebSocketClose.")
         opening.future.onSuccess {
            case open => ws.close()
         }

      case Request(n) =>
         log.debug(s"Received request($n). We have ${receiveQueue.size} messages in queue.")
         while (totalDemand > 0 && receiveQueue.nonEmpty) {
            onNext(receiveQueue.dequeue())
         }

      case Cancel =>
   }
}

class RoutePublisher(descriptor: String) extends ActorPublisher[ActorRef] with ActorLogging {

   val openQueue = mutable.Queue[ActorRef]()
   val connections = mutable.Map[WebSocket, ActorRef]()

   def receive = {
      case RemoveChild(ws) =>
         log.debug(s"Removing child: " + ws.getRemoteSocketAddress)
         connections -= ws

      case (ws: WebSocket, msg: String) =>
         connections(ws) ! msg

      case (ws: WebSocket, e: Error) =>
         connections(ws) ! e

      case (ws: WebSocket, c: Close) =>
         connections(ws) ! c

      case (ws: WebSocket, open: ServerOpen) =>
         log.debug(s"Opening new connection ${ws.getRemoteSocketAddress}.")
         val websocket = context.actorOf(Props(classOf[WebSocketPublisher], ws))
         websocket ! open
         connections += (ws -> websocket)
         if (totalDemand > 0) {
            onNext(websocket)
         } else {
            openQueue += websocket
         }

      case Request(n) =>
         log.debug(s"Received request($n). We have ${openQueue.size} openings in queue.")
         while (totalDemand > 0 && openQueue.nonEmpty) {
            onNext(openQueue.dequeue())
         }

      case Cancel =>
   }
}


class ReactiveServer(val port: Int) extends WebSocketServer(new InetSocketAddress(port)) with Actor with ActorLogging {

   val handlers = mutable.Map[String, ActorRef]()

   start()

   final override def onOpen(ws: WebSocket, hs: ClientHandshake) = findHandler(ws) {
      handler => handler ! (ws, ServerOpen(hs))
   }

   final override def onClose(ws: WebSocket, code: Int, reason: String, external: Boolean) = findHandler(ws) {
      handler => handler ! (ws, Close(code, reason, external))
   }

   final override def onError(ws: WebSocket, ex: Exception) = findHandler(ws) {
      handler => handler ! (ws, Error(ex))
   }

   final override def onMessage(ws: WebSocket, msg: String) = findHandler(ws) {
      handler => handler ! (ws, msg)
   }

   private def findHandler(ws: WebSocket)(handle: ActorRef => Unit) = {
      if (ws != null) {
         handlers.get(ws.getResourceDescriptor) match {
            case Some(publisher) => handle(publisher)
            case None => ws.close(CloseFrame.REFUSE)
         }
      }
   }

   def receive = {
      case SubscribeForResource(description) =>
         sender() ! ResourceSubscription(sourceFor(description))
   }

   final def sourceFor(descriptor: String) = {
      val publisher = context.actorOf(Props(classOf[RoutePublisher], descriptor))
      handlers += descriptor -> publisher
      Source(ActorPublisher[ActorRef](publisher))
   }
}
