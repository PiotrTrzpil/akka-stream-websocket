package streamwebsocket

import akka.stream.actor.{WatermarkRequestStrategy, ActorSubscriberMessage, ActorSubscriber, ActorPublisher}
import spray.can.websocket.frame.Frame
import scala.collection.mutable
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.actor._
import spray.http.{HttpMethods, HttpHeaders}
import spray.can.{Http, websocket}
import akka.io.IO
import spray.can.server.UHttp
import spray.http.HttpRequest
import spray.can.websocket.Send
import akka.stream.actor.ActorPublisherMessage.Request

class APublisher extends ActorPublisher[Frame] {
   val receiveQueue = mutable.Queue[Frame]()
   def receive = {
      case f:Frame => receiveQueue.enqueue(f)
         println("publisher client got Frame"+f)
         process()
      case Request(n) =>
         println("publisher client got Request"+n)
         process()

      case Cancel =>
         self ! PoisonPill
   }
   def process() = {
      while (totalDemand > 0 && receiveQueue.nonEmpty) {
         onNext(receiveQueue.dequeue())
      }
   }
}
class ASubscriber(client:ActorRef) extends ActorSubscriber with ActorLogging{
   def receive = {
      case ActorSubscriberMessage.OnError(ex) =>
         log.error("",ex)
      case ActorSubscriberMessage.OnComplete =>
         log.info("on client COMPLETE")
      case ActorSubscriberMessage.OnNext(msg :Frame) =>
         log.info("on client ONNEXT"+msg)
         client ! Send(msg)
   }

   protected def requestStrategy = WatermarkRequestStrategy(10)
}

class Client(commander:ActorRef, val upgradeRequest: HttpRequest) extends websocket.WebSocketClientWorker {
   var subscriber:ActorRef = _
   var publisher:ActorRef = _

   def businessLogic: Receive = {
      case websocket.UpgradedToWebSocket =>
         log.info("on client websocket.UpgradedToWebSocket")
         publisher = context.actorOf(Props(classOf[APublisher]), "client-publisher")
         subscriber = context.actorOf(Props(classOf[ASubscriber], self),"client-subscriber")
         commander ! Websocket.Connection(ActorPublisher(publisher), ActorSubscriber(subscriber))
      case Send(frame) => connection ! frame
         log.info("on client Send(frame)")
      case frame:Frame =>
         publisher ! frame
         log.info("on client receive(frame)")
      //val str = text.utf8String
      //println("client received: "+str)
      //probe.ref ! s"client received: "+str

      case _: Http.ConnectionClosed =>
         context.stop(self)
      //  case x => connection ! x
   }

}
object WebSocketClient {
   def props() = Props(classOf[WebSocketClient])
}
class WebSocketClient extends Actor {
   implicit val sys = context.system
   def receive = {
      case Websocket.Connect(host, port, path) =>
         val headers = List(
            HttpHeaders.Host(host, port),
            HttpHeaders.Connection("Upgrade"),
            HttpHeaders.RawHeader("Upgrade", "websocket"),
            HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
            HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
            HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

         val client = context.actorOf(Props(new Client(sender(), HttpRequest(HttpMethods.GET, path, headers))), "CLIENT")
         IO(UHttp).tell(Http.Connect(host, port, false), client)
   }
}