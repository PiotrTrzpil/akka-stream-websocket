package streamwebsocket


import akka.actor._
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{TextFrame, Frame, BinaryFrame}
import spray.routing.HttpServiceActor
import akka.stream.actor._
import akka.stream.actor.ActorPublisherMessage.Cancel
import spray.http.HttpRequest
import spray.can.websocket.FrameCommandFailed
import akka.stream.actor.ActorPublisherMessage.Request
import scala.collection.mutable
import org.reactivestreams.{Subscriber, Publisher}
import streamwebsocket.WebSocketMessage.Connection
import java.net.InetSocketAddress
import streamwebsocket.WebSocketServer.Push
import spray.http.HttpRequest
import streamwebsocket.WebSocketMessage.Connection
import streamwebsocket.WebSocketServer.Push
import spray.can.websocket.FrameCommandFailed
import akka.stream.actor.ActorPublisherMessage.Request
import akka.event.LoggingReceive

case object WebSocketMessage {
   case class Bound(address: InetSocketAddress, connections:Publisher[WebSocketMessage.Connection])
   case class Connect(host:String, port:Int, path:String)
   case object Unbind
   case class Bind(host:String, port:Int)
   case class Connection(inbound : Publisher[Frame], outbound:Subscriber[Frame])
}

object WebSocketServer {
   def props() = Props(classOf[WebSocketServer])
   final case class Push(msg: Frame)
}

class WebSocketServer() extends Actor with ActorLogging {
   implicit val sys = context.system
   def receive = LoggingReceive {
      case WebSocketMessage.Bind(host, port) =>
         IO(UHttp) ! Http.Bind(self, host, port)
         context.become(awaitingBound(sender()))
   }
   def awaitingBound(commander : ActorRef): Receive = {
      case Http.Bound(address) =>
         val connectionPublisher = context.actorOf(Props(classOf[ConnectionPublisher]), "conn-publisher")
         commander ! WebSocketMessage.Bound(address, ActorPublisher(connectionPublisher))
         context.become(connected(connectionPublisher))
   }
   def connected(connectionPublisher:ActorRef): Receive = {
      case Http.Connected(remoteAddress, localAddress) =>
         val serverConnection = sender()

         log.debug(s"Websocket server received a new connection from: "+remoteAddress)
         val worker = context.actorOf(ServerWorker.props(serverConnection, connectionPublisher))
         serverConnection ! Http.Register(worker)

      case WebSocketMessage.Unbind =>
         IO(UHttp) ! Http.Unbind
   }
}

object ServerWorker {
   def props(serverConnection: ActorRef, publisher:ActorRef) = Props(classOf[ServerWorker], serverConnection, publisher)
}

class ConnectionPublisher extends ActorPublisher[WebSocketMessage.Connection] {
   val connectionsQueue = mutable.Queue[WebSocketMessage.Connection]()
   def receive = LoggingReceive {
      case f:WebSocketMessage.Connection =>
         connectionsQueue.enqueue(f)
         process()
      case Request(n) =>
         process()
      case Cancel =>
         self ! PoisonPill
   }
   def process() = {
      while (totalDemand > 0 && connectionsQueue.nonEmpty) {
         onNext(connectionsQueue.dequeue())
      }
   }
}

class ServerPublisher extends ActorPublisher[Frame] {
   val receiveQueue = mutable.Queue[Frame]()
   def receive = LoggingReceive {
      case f:Frame =>
         receiveQueue.enqueue(f)
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
class ServerSubscriber(connection:ActorRef) extends ActorSubscriber with ActorLogging {
   def receive = LoggingReceive {
      case ActorSubscriberMessage.OnError(ex) =>
         log.error("Server subscriber websocket stream error. {}",ex.toString)
      case ActorSubscriberMessage.OnComplete =>
         log.debug("Server subscriber websocket stream complete.")
      case ActorSubscriberMessage.OnNext(msg :Frame) =>
         connection ! Push(msg)
   }

   protected def requestStrategy = WatermarkRequestStrategy(10)
}

class ServerWorker(val serverConnection: ActorRef, val connectionPublisher: ActorRef) extends HttpServiceActor with websocket.WebSocketServerWorker {

   val subscriber:ActorRef = context.actorOf(Props(classOf[ServerSubscriber], self))
   val publisher:ActorRef = context.actorOf(Props(classOf[ServerPublisher]))
   log.debug(s"Sending a new server connection to connection publisher: "+connectionPublisher)
   connectionPublisher ! Connection(ActorPublisher[Frame](publisher), ActorSubscriber[Frame](subscriber))

   override def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

   def businessLogic = LoggingReceive {
      case x @ (_: BinaryFrame | _: TextFrame) =>
         log.debug(s"Received websocket frame on the server.")
         publisher ! x
      case Push(msg) =>
         log.debug(s"Sending websocket frame from server.")
         send(msg)
      case x: FrameCommandFailed =>
         log.error("Frame command failed on websocket server connection.", x)
         self ! PoisonPill
      case x: HttpRequest =>
   }

   def businessLogicNoUpgrade: Receive = {
      implicit val refFactory: ActorRefFactory = context
      runRoute {
         getFromResourceDirectory("webapp")
      }
   }
}

