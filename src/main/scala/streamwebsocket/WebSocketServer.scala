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
   def receive = {
      case WebSocketMessage.Bind(host, port) =>
         IO(UHttp) ! Http.Bind(self, host, port)
         context.become(awaitingBound(sender()))
   }
   def awaitingBound(commander : ActorRef):Receive = {
      case Http.Bound(address) =>
         val connectionPublisher = context.actorOf(Props(classOf[ConnectionPublisher]), "conn-publisher")
         commander ! WebSocketMessage.Bound(address, ActorPublisher(connectionPublisher))
         context.become(connected(commander, connectionPublisher))
   }
   def connected(commander: ActorRef, connectionPublisher:ActorRef): Receive = {
      case Http.Connected(remoteAddress, localAddress) =>
         val serverConnection = sender()
         val publisher:ActorRef = context.actorOf(Props(classOf[ServerPublisher]))
         val worker = context.actorOf(ServerWorker.props(serverConnection, publisher))
         serverConnection ! Http.Register(worker)
         val subscriber:ActorRef = context.actorOf(Props(classOf[ServerSubscriber], worker))
         connectionPublisher ! Connection(ActorPublisher[Frame](publisher), ActorSubscriber[Frame](subscriber))
      case WebSocketMessage.Unbind =>
         IO(UHttp) ! Http.Unbind
   }
}

object ServerWorker {
   def props(serverConnection: ActorRef, publisher:ActorRef) = Props(classOf[ServerWorker], serverConnection, publisher)
}

class ConnectionPublisher extends ActorPublisher[WebSocketMessage.Connection] {
   val connectionsQueue = mutable.Queue[WebSocketMessage.Connection]()
   def receive = {
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
   def receive = {
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
class ServerSubscriber(connection:ActorRef) extends ActorSubscriber with ActorLogging{
   def receive = {
      case ActorSubscriberMessage.OnError(ex) =>
         log.error("",ex)
      case ActorSubscriberMessage.OnComplete =>
      case ActorSubscriberMessage.OnNext(msg :Frame) =>
         connection ! Push(msg)
   }

   protected def requestStrategy = OneByOneRequestStrategy
}

class ServerWorker(val serverConnection: ActorRef, val publisher: ActorRef) extends HttpServiceActor with websocket.WebSocketServerWorker {
   override def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

   def businessLogic: Receive = {
      case x @ (_: BinaryFrame | _: TextFrame) =>
         publisher ! x
      case Push(msg) =>
         send(msg)
      case x: FrameCommandFailed =>
         log.error("Frame command failed", x)
      case x: HttpRequest =>
   }

   def businessLogicNoUpgrade: Receive = {
      implicit val refFactory: ActorRefFactory = context
      runRoute {
         getFromResourceDirectory("webapp")
      }
   }
}

