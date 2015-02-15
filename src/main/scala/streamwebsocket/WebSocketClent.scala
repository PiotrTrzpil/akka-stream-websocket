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
import akka.event.LoggingReceive

class ClientPublisher extends ActorPublisher[Frame] {
   val receiveQueue = mutable.Queue[Frame]()
   def receive = LoggingReceive {
      case f:Frame => receiveQueue.enqueue(f)
         process()
      case Request(n) =>
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
class ClientSubscriber(client:ActorRef) extends ActorSubscriber with ActorLogging{
   def receive = LoggingReceive {
      case ActorSubscriberMessage.OnError(ex) =>
         log.error("Received stream error on websocket client.",ex)
         client ! PoisonPill
      case ActorSubscriberMessage.OnComplete =>
         log.info("End of stream on websocket client.")
        // client ! PoisonPill
      case ActorSubscriberMessage.OnNext(msg :Frame) =>
         client ! Send(msg)
   }

   protected def requestStrategy = WatermarkRequestStrategy(10)
}


object WebSocketClient {
   def props() = Props(classOf[WebSocketClient])
}
class WebSocketClient extends Actor with ActorLogging {
   implicit val sys = context.system
   def receive = LoggingReceive {
      case connect @ WebSocketMessage.Connect(host, port, path) =>
         val headers = List(
            HttpHeaders.Host(host, port),
            HttpHeaders.Connection("Upgrade"),
            HttpHeaders.RawHeader("Upgrade", "websocket"),
            HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
            HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
            HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))
         log.debug(s"Received connection request from ${sender()}")
         val client = context.actorOf(Props(classOf[ClientWorker], sender(), HttpRequest(HttpMethods.GET, path, headers), connect))
         IO(UHttp).tell(Http.Connect(host, port, sslEncryption = false), client)
   }
}

class ClientWorker(commander:ActorRef, val upgradeRequest: HttpRequest, connect:WebSocketMessage.Connect)
  extends websocket.WebSocketClientWorker {
   var subscriber:ActorRef = _
   var publisher:ActorRef = _

   def businessLogic = LoggingReceive {
      case websocket.UpgradedToWebSocket =>
         publisher = context.actorOf(Props(classOf[ClientPublisher]), "client-publisher")
         subscriber = context.actorOf(Props(classOf[ClientSubscriber], self),"client-subscriber")
         log.debug(s"Sending established connection to ${commander}")
         commander ! WebSocketMessage.Connection(ActorPublisher(publisher), ActorSubscriber(subscriber))
      case Send(frame) =>
         log.debug(s"Sending websocket frame from client to ${connect.host}:${connect.port}")
         connection ! frame
      case frame:Frame =>
         log.debug(s"Received websocket frame on client from ${connect.host}:${connect.port}")
         publisher ! frame
      case _: Http.ConnectionClosed =>
         context.stop(self)
   }

}