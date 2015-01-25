package streamwebsocket


import akka.actor._
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{TextFrame, Frame, BinaryFrame}
import spray.routing.HttpServiceActor
import akka.stream.actor.{WatermarkRequestStrategy, ActorSubscriberMessage, ActorSubscriber, ActorPublisher}
import akka.stream.actor.ActorPublisherMessage.Cancel
import spray.http.HttpRequest
import spray.can.websocket.FrameCommandFailed
import akka.stream.actor.ActorPublisherMessage.Request
import scala.collection.mutable
import org.reactivestreams.{Subscriber, Publisher}
import streamwebsocket.Websocket.Connection
import scala.concurrent.Future
import scala.util.Try
import java.net.InetSocketAddress


case object Websocket {
   case class Bound(address: InetSocketAddress)

   case class Connect(host:String, port:Int, path:String)

   case object Unbind

   case class Bind(host:String, port:Int)
   case class Connection(inbound : Publisher[Frame], outbound:Subscriber[Frame])

}

object SimpleServer extends App {

   final case class Push(msg: Frame)

   object WebSocketServer {
      def props() = Props(classOf[WebSocketServer])
   }

   class WebSocketServer() extends Actor with ActorLogging {
      implicit val sys = context.system
      def receive = {
         case Websocket.Bind(host, port) =>
            IO(UHttp) ! Http.Bind(self, host, port)
            context.become(awaitingBound(sender()))
      }
      def awaitingBound(commander : ActorRef):Receive = {
         case Http.Bound(address) =>
            println("BOUND!!!")
            commander ! Websocket.Bound(address)
            context.become(connected(commander))
      }
      def connected(commander : ActorRef): Receive = {
         case Http.Connected(remoteAddress, localAddress) =>
            val serverConnection = sender()
            val publisher:ActorRef = context.actorOf(Props(classOf[APublisher]))
            val worker = context.actorOf(WebSocketWorker.props(serverConnection, publisher))
            serverConnection ! Http.Register(worker)
            val subscriber:ActorRef = context.actorOf(Props(classOf[ASubscriber], worker))
            println("Sending connection!!!")
            commander ! Connection(ActorPublisher[Frame](publisher), ActorSubscriber[Frame](subscriber))

         case Websocket.Unbind =>
            IO(UHttp) ! Http.Unbind
            //serverConnection ! PoisonPill
      }
   }

   object WebSocketWorker {
      def props(serverConnection: ActorRef, publisher:ActorRef) = Props(classOf[WebSocketWorker], serverConnection, publisher)
   }

   class APublisher extends ActorPublisher[Frame] {
      val receiveQueue = mutable.Queue[Frame]()
      def receive = {
         case f:Frame =>
            println("publisher got Frame"+f)
            try {
               receiveQueue.enqueue(f)

               process()
            } catch {
               case (ex:Exception) => println(ex)
            }

         case Request(n) =>
            println("publisher got Request"+n)
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
   class ASubscriber(connection:ActorRef) extends ActorSubscriber with ActorLogging{
      def receive = {
         case ActorSubscriberMessage.OnError(ex) =>
            log.error("",ex)
         case ActorSubscriberMessage.OnComplete =>
            log.info("on COMPLETE")
         case ActorSubscriberMessage.OnNext(msg :Frame) =>
            log.info("on ONNEXT"+msg)
            connection ! Push(msg)
      }

      protected def requestStrategy = WatermarkRequestStrategy(10)
   }

   class WebSocketWorker(val serverConnection: ActorRef, val publisher: ActorRef) extends HttpServiceActor with websocket.WebSocketServerWorker {
      override def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

      println("on WebSocketWorker")
      log.info("on WebSocketWorker")

      def businessLogic: Receive = {
         // just bounce frames back for Autobahn testsuite
         case x @ (_: BinaryFrame | _: TextFrame) =>
            log.info("sending to publisher"+x)
            publisher ! x
         case Push(msg) =>
            log.info("Push"+msg)
            send(msg)

         case x: FrameCommandFailed =>
            log.error("frame command failed", x)

         case x: HttpRequest => // do something
      }

      def businessLogicNoUpgrade: Receive = {
         implicit val refFactory: ActorRefFactory = context
         runRoute {
            getFromResourceDirectory("webapp")
         }
      }
   }

//   def doMain() {
//      implicit val system = ActorSystem()
//
//      val server = system.actorOf(WebSocketServer.props(), "websocket")
//
//      IO(UHttp) ! Http.Bind(server, "localhost", 8080)
//
//      readLine("Hit ENTER to exit ...\n")
//      system.shutdown()
//      system.awaitTermination()
//   }

   // because otherwise we get an ambiguous implicit if doMain is inlined
 //  doMain()
}