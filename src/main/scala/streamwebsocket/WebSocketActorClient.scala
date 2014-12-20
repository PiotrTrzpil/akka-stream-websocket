package streamwebsocket

import java.net.URI

import akka.actor.{ActorLogging, PoisonPill}
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.actor.{ActorSubscriberMessage, ActorSubscriber, OneByOneRequestStrategy, ActorPublisher}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.{ClientHandshake, ServerHandshake}
import streamwebsocket.WebSocketMessage._

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.Success
import org.reactivestreams.{Subscription, Subscriber, Processor}
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.contrib.streamwebsocket.WebSocket.{Event, Message, Command}


case class WebSocketSend(message:String)
case object WebSocketClose


object WebSocketMessage {
   case class Message(message: String) extends Event
   case class Close(code:Int, reason:String, isRemote:Boolean) extends Event
   case class Error(cause : Throwable) extends Event
   case class ClientOpen(hs: ServerHandshake) extends Event
   case class ServerOpen(hs: ClientHandshake)extends Event
   case object SubscribeClose
   case object SubscribeOpen

}



class WebSocketActorClient( str: String)
  extends WebSocketClient(URI.create(str))
  with ActorPublisher[String] with ActorSubscriber with ActorLogging {

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
//      case OnNext() =>
//         sender() ! opening.future

      case close : WebSocketMessage.Close =>
         closing.complete(Success(close))
         onComplete()


      case WebSocketClose =>
         opening.future.onSuccess {
            case open => close()
         }


      case OnNext(message:String) =>
         if (opening.isCompleted) {
            send(message)
         } else {
            sendQueue += message
         }
//      case ActorSubscriberMessage.OnComplete =>
//      // TODO - blank out the screen
//      case ActorSubscriberMessage.OnError(err) =>
//      // TODO - display error.

      case Request(n) =>
         while (totalDemand > 0 && receiveQueue.nonEmpty) {
            val d = receiveQueue.dequeue()
            log.info("Invoking onNext("+d)
            onNext(d)
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

   def onSubscribe(s: Subscription) = ???

}
