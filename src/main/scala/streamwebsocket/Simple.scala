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

case class Register(publisher : Publisher[Frame], subscriber:Subscriber[Frame])

object SimpleServer extends App {

   final case class Push(msg: String)

   object WebSocketServer {
      def props(listener:ActorRef) = Props(classOf[WebSocketServer], listener)
   }
   class WebSocketServer(listener:ActorRef) extends Actor with ActorLogging {
      def receive = {
         // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
         case Http.Connected(remoteAddress, localAddress) =>
            log.info("on CONNECTED")
            val serverConnection = sender()
            val publisher:ActorRef = context.actorOf(Props(classOf[APublisher]))
            val conn = context.actorOf(WebSocketWorker.props(serverConnection, publisher))
            serverConnection ! Http.Register(conn)
            val subscriber:ActorRef = context.actorOf(Props(classOf[ASubscriber], conn))

            listener ! Register(ActorPublisher[Frame](publisher), ActorSubscriber[Frame](subscriber))
      }
   }

   object WebSocketWorker {
      def props(serverConnection: ActorRef, publisher:ActorRef) = Props(classOf[WebSocketWorker], serverConnection, publisher)
   }

   class APublisher extends ActorPublisher[Frame] {
      val receiveQueue = mutable.Queue[Frame]()
      def receive = {
         case f:Frame => receiveQueue.enqueue(f)
            println("publisher got Frame"+f)
            process()
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
         //   connection ! Push(msg)
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
            send(TextFrame(msg))

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