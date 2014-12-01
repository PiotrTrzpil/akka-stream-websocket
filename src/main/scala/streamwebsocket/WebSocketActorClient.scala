package streamwebsocket

import java.net.URI

import akka.actor.PoisonPill
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.actor.{OneByOneRequestStrategy, ActorPublisher}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.{ClientHandshake, ServerHandshake}
import streamwebsocket.WebSocketMessage._

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.Success


case class WebSocketSend(message:String)
case object WebSocketClose


object WebSocketMessage {
   case class Message(message: String)
   case class Close(code:Int, reason:String, isRemote:Boolean)
   case class Error(cause : Throwable)
   case class ClientOpen(hs: ServerHandshake)
   case class ServerOpen(hs: ClientHandshake)
   case object SubscribeClose
   case object SubscribeOpen

}



class WebSocketActorClient( str: String)
  extends WebSocketClient(URI.create(str)) with ActorPublisher[String] {

   protected def requestStrategy = OneByOneRequestStrategy
   implicit val exec = context.system.dispatcher

   val receiveQueue = mutable.Queue[String]()
   val sendQueue = mutable.Queue[String]()

   val closing = Promise[WebSocketMessage.Close]()
   val opening = Promise[WebSocketMessage.ClientOpen]()

   connect()


   def receive = {
      case message : String =>
         if (totalDemand > 0) {
            onNext(message)
         } else {
            receiveQueue += message
         }

      case open: WebSocketMessage.ClientOpen =>
         opening.complete(Success(open))
         while (sendQueue.nonEmpty) {
            send(sendQueue.dequeue())
         }

      case WebSocketMessage.Error(cause) =>
         onError(cause)

      case SubscribeClose =>
         sender() ! closing.future

      case SubscribeOpen =>
         sender() ! opening.future

      case close : WebSocketMessage.Close =>
         closing.complete(Success(close))
         onComplete()

      case WebSocketSend(message) =>
         if (opening.isCompleted) {
            send(message)
         } else {
            sendQueue += message
         }

      case WebSocketClose =>
         opening.future.onSuccess {
            case open => close()
         }

      case Request(n) =>
         while (totalDemand > 0 && receiveQueue.nonEmpty) {
            onNext(receiveQueue.dequeue())
         }

      case Cancel =>
         this.close()
         self ! PoisonPill
   }

   def onOpen(sh: ServerHandshake) = {
      self ! WebSocketMessage.ClientOpen(sh)
   }

   def onError(e: Exception) = {
      self ! WebSocketMessage.Error(e)
   }

   def onMessage(message: String) = {
      self ! message
   }

   def onClose(i: Int, s: String, b: Boolean) = {
      self ! WebSocketMessage.Close(i, s, b)
   }
}
