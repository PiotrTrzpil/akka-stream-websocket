package akka.stream.contrib.streamwebsocket

import akka.actor.{ExtendedActorSystem, ActorSystem, ExtensionIdProvider, ExtensionId}
import akka.stream.scaladsl.{Flow, Source, MaterializedMap}
import scala.concurrent._
import java.net.{URI, InetSocketAddress}
import akka.util.ByteString
import akka.stream.FlowMaterializer
import scala.util.control.NoStackTrace
import akka.stream.io.StreamTcp
import akka.stream.impl._
import akka.stream.io.TcpStreamActor.{WriteAck, TcpStreamException}
import org.java_websocket.client.WebSocketClient
import akka.stream.contrib.streamwebsocket.WebSocket.Command
import akka.stream.actor._
import akka.stream.actor.ActorPublisher
import akka.io.Tcp._
import scala.util.Failure
import scala.util.Success
import akka.stream.impl.TransferPhase
import akka.stream.contrib.streamwebsocket.WebSocket.Connected
import akka.stream.contrib.streamwebsocket.WebSocket.CommandFailed
import akka.io.Tcp.Bind
import org.java_websocket.handshake.ServerHandshake
import streamwebsocket.WebSocketMessage
import scala.collection.mutable
import akka.stream.contrib.streamwebsocket.WebSocket
import akka.actor.Actor.Receive
import scala.util.Failure
import akka.stream.impl.ActorBasedFlowMaterializer
import scala.Some
import scala.util.Success
import akka.stream.impl.TransferPhase
import akka.stream.contrib.streamwebsocket.WebSocket.Connected
import akka.io.Tcp.Connect
import akka.stream.contrib.streamwebsocket.WebSocket.CommandFailed
import akka.io.Tcp.Bind

object StreamWebSocket extends ExtensionId[StreamWebSocketExt] with ExtensionIdProvider {

   /**
    * Represents a prospective TCP server binding.
    */
   trait ServerBinding {
      /**
       * The local address of the endpoint bound by the materialization of the `connections` [[Source]]
       * whose [[MaterializedMap]] is passed as parameter.
       */
      def localAddress(materializedMap: MaterializedMap): Future[InetSocketAddress]

      /**
       * The stream of accepted incoming connections.
       * Can be materialized several times but only one subscription can be "live" at one time, i.e.
       * subsequent materializations will reject subscriptions with an [[BindFailedException]] if the previous
       * materialization still has an uncancelled subscription.
       * Cancelling the subscription to a materialization of this source will cause the listening port to be unbound.
       */
      def connections: Source[IncomingConnection]

      /**
       * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
       * [[Source]] whose [[MaterializedMap]] is passed as parameter.
       *
       * The produced [[Future]] is fulfilled when the unbinding has been completed.
       */
      def unbind(materializedMap: MaterializedMap): Future[Unit]
   }

   /**
    * Represents an accepted incoming TCP connection.
    */
   trait IncomingConnection {
      /**
       * The local address this connection is bound to.
       */
      def localAddress: InetSocketAddress

      /**
       * The remote address this connection is bound to.
       */
      def remoteAddress: InetSocketAddress

      /**
       * Handles the connection using the given flow, which is materialized exactly once and the respective
       * [[MaterializedMap]] returned.
       *
       * Convenience shortcut for: `flow.join(handler).run()`.
       */
      def handleWith(handler: Flow[ByteString, ByteString])(implicit materializer: FlowMaterializer): MaterializedMap

      /**
       * A flow representing the client on the other side of the connection.
       * This flow can be materialized only once.
       */
      def flow: Flow[ByteString, ByteString]
   }

   /**
    * Represents a prospective outgoing TCP connection.
    */
   sealed trait OutgoingConnection {
      /**
       * The remote address this connection is or will be bound to.
       */
      def remoteAddress: InetSocketAddress

      /**
       * The local address of the endpoint bound by the materialization of the connection materialization
       * whose [[MaterializedMap]] is passed as parameter.
       */
      def localAddress(mMap: MaterializedMap): Future[InetSocketAddress]

      /**
       * Handles the connection using the given flow.
       * This method can be called several times, every call will materialize the given flow exactly once thereby
       * triggering a new connection attempt to the `remoteAddress`.
       * If the connection cannot be established the materialized stream will immediately be terminated
       * with a [[ConnectionAttemptFailedException]].
       *
       * Convenience shortcut for: `flow.join(handler).run()`.
       */
      def handleWith(handler: Flow[String, String])(implicit materializer: FlowMaterializer): MaterializedMap

      /**
       * A flow representing the server on the other side of the connection.
       * This flow can be materialized several times, every materialization will open a new connection to the
       * `remoteAddress`. If the connection cannot be established the materialized stream will immediately be terminated
       * with a [[ConnectionAttemptFailedException]].
       */
      def flow: Flow[String, String]
   }

   case object BindFailedException extends RuntimeException with NoStackTrace

   class ConnectionException(message: String) extends RuntimeException(message)

   class ConnectionAttemptFailedException(val endpoint: InetSocketAddress) extends ConnectionException(s"Connection attempt to $endpoint failed")

   //////////////////// EXTENSION SETUP ///////////////////

   def apply()(implicit system: ActorSystem): StreamWebSocketExt = super.apply(system)

   def lookup() = StreamTcp

   def createExtension(system: ExtendedActorSystem): StreamWebSocketExt = new StreamWebSocketExt(system)
}


import akka.actor._
import akka.stream.io._
import akka.stream.scaladsl._
import scala.concurrent.{Promise, Future}
import java.net.{URLEncoder, InetSocketAddress}
import akka.util.ByteString
import akka.stream.{MaterializerSettings, FlowMaterializer}
import scala.collection.immutable
import akka.io.Inet.SocketOption
import scala.concurrent.duration.{FiniteDuration, Duration}
import org.reactivestreams.{Processor, Subscriber}
import akka.stream.impl.{ActorProcessor, ActorBasedFlowMaterializer}
import scala.Some


class StreamWebSocketExt(system: ExtendedActorSystem) extends akka.actor.Extension {
   import StreamWebSocketExt._
   import StreamWebSocket._
   import system.dispatcher

   private val manager: ActorRef = system.systemActorOf(Props[StreamWebSocketManager], name = "IO-WEBSOCKET-STREAM")


   def bind(endpoint: InetSocketAddress): ServerBinding = {
      val connectionSource = new KeyedActorFlowSource[IncomingConnection, (Future[InetSocketAddress], Future[() ⇒ Future[Unit]])] {
         override def attach(flowSubscriber: Subscriber[IncomingConnection],
                             materializer: ActorBasedFlowMaterializer,
                             flowName: String): MaterializedType = {
            val localAddressPromise = Promise[InetSocketAddress]()
            val unbindPromise = Promise[() ⇒ Future[Unit]]()
            manager ! StreamWebSocketManager.Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint)
            localAddressPromise.future -> unbindPromise.future
         }
      }
      new ServerBinding {
         def localAddress(mm: MaterializedMap) = mm.get(connectionSource)._1
         def connections = connectionSource
         def unbind(mm: MaterializedMap): Future[Unit] = mm.get(connectionSource)._2.flatMap(_())
      }
   }

   /**
    * Creates an [[OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
    */
   def outgoingConnection(remoteAddress: InetSocketAddress,
                          localAddress: Option[InetSocketAddress] = None,
                          options: immutable.Traversable[SocketOption] = Nil,
                          connectTimeout: Duration = Duration.Inf,
                          idleTimeout: Duration = Duration.Inf): OutgoingConnection = {
      val remoteAddr = remoteAddress
      val key = new PreMaterializedOutgoingKey()



//      Flow()
      val stream = Pipe(key) { () ⇒
         val processorPromise = Promise[Processor[String, String]]()
         val localAddressPromise = Promise[InetSocketAddress]()
         manager ! StreamWebSocketManager.Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options,
            connectTimeout, idleTimeout)
         (new DelayedInitProcessor[String, String](processorPromise.future), localAddressPromise.future)
      }
//      system.actorOf(Props(classOf[]))
//      val sink = Sink(ActorSubscriber())

//      manager ! StreamWebSocketManager.Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options,
//         connectTimeout, idleTimeout)


      new OutgoingConnection {
         def remoteAddress = remoteAddr
        // def localAddress(mm: MaterializedMap) = mm.get(key)
         def flow = stream
         def handleWith(handler: Flow[String, String])(implicit fm: FlowMaterializer) =
            flow.join(handler).run()
      }
   }
}
case object WaitingComplete

class InitialClientActor(implFuture: Future[ActorRef]) extends ActorSubscriber with ActorPublisher[String] with Stash {

   protected def requestStrategy = OneByOneRequestStrategy
   var impl:ActorRef = _

   implFuture.onComplete {
      case Success(value) =>
         impl = value
         self ! WaitingComplete
      case Failure(ex) =>
         onError(ex)
   }
   def receive = {
      case WaitingComplete =>
         unstashAll()
         context.become(implReceive)
      case _ => stash()
      case ActorSubscriberMessage.OnNext(el) =>
      case ActorSubscriberMessage.OnError(ex) =>
      case ActorSubscriberMessage.OnComplete =>
      case ActorPublisherMessage.Request(n) =>
      case ActorPublisherMessage.Cancel =>
   }

   def implReceive : Receive = {
      case a => stash()
   }
}

/**
 * INTERNAL API
 */
object StreamWebSocketExt {
   /**
    * INTERNAL API
    */
   class PreMaterializedOutgoingKey extends Key[Future[InetSocketAddress]] {
      override def materialize(map: MaterializedMap) =
         throw new IllegalStateException("This key has already been materialized by the TCP Processor")
   }
}


/**
 * INTERNAL API
 */
object StreamWebSocketManager {
   /**
    * INTERNAL API
    */
   case class Connect(processorPromise: Promise[Processor[String, String]],
                      localAddressPromise: Promise[InetSocketAddress],
                      remoteAddress: InetSocketAddress,
                      localAddress: Option[InetSocketAddress],
                      options: immutable.Traversable[SocketOption],
                      connectTimeout: Duration,
                      idleTimeout: Duration)

   /**
    * INTERNAL API
    */
   case class Bind(localAddressPromise: Promise[InetSocketAddress],
                   unbindPromise: Promise[() ⇒ Future[Unit]],
                   flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
                   endpoint: InetSocketAddress,
                   backlog: Int,
                   options: immutable.Traversable[SocketOption],
                   idleTimeout: Duration)

   /**
    * INTERNAL API
    */
   case class ExposedProcessor(processor: Processor[String, String])

}
object WebSocket {

   case class Connect(remoteAddress: InetSocketAddress,
                      localAddress: Option[InetSocketAddress] = None,
                      options: immutable.Traversable[SocketOption] = Nil,
                      timeout: Option[FiniteDuration] = None,
                      pullMode: Boolean = false) extends Command

   case class Bind(handler: ActorRef,
                   localAddress: InetSocketAddress,
                   backlog: Int = 100,
                   options: immutable.Traversable[SocketOption] = Nil,
                   pullMode: Boolean = false) extends Command


   case class Abort() extends Command
   case class Close() extends Command
   case class Register(handler: ActorRef) extends Command
   trait Message
   trait Command extends Message
   trait Event extends Message
   case class Connected(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress) extends Event
   case class CommandFailed(cmd: Command) extends Event
}
/**
 * INTERNAL API
 */
class StreamWebSocketManager extends Actor {
   import StreamWebSocketManager._

   var nameCounter = 0
   def encName(prefix: String, endpoint: InetSocketAddress) = {
      nameCounter += 1
      s"$prefix-$nameCounter-${URLEncoder.encode(endpoint.toString, "utf-8")}"
   }

   def receive: Receive = {
      case Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options, connectTimeout, _) ⇒
         val connTimeout = connectTimeout match {
            case x: FiniteDuration ⇒ Some(x)
            case _                 ⇒ None
         }
         val processorActor = context.actorOf(WebSocketStreamActor.clientProps(processorPromise, localAddressPromise,
            WebSocket.Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
            materializerSettings = MaterializerSettings(context.system)), name = encName("client", remoteAddress))

        // val client = context.actorOf(Props(classOf[WebSocketActorClient], remoteAddress))
        // client.
           processorActor ! ExposedProcessor(ActorProcessor[String, String](processorActor))

      case Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint, backlog, options, _) ⇒
         val publisherActor = context.actorOf(WebSocketListenStreamActor.props(localAddressPromise, unbindPromise,
            flowSubscriber, endpoint, MaterializerSettings(context.system)), name = encName("server", endpoint))
      // this sends the ExposedPublisher message to the publisher actor automatically
      //ActorPublisher[Any](publisherActor)
   }
}
object WebSocketStreamActor {
   def clientProps(processorPromise: Promise[Processor[String, String]],
                     localAddressPromise: Promise[InetSocketAddress],
                     connectCmd: WebSocket.Connect,
                     materializerSettings: MaterializerSettings): Props =
      Props(new WebSocketStreamClientActor(processorPromise, localAddressPromise, connectCmd,
         materializerSettings)).withDispatcher(materializerSettings.dispatcher)


   def serverProps(localAddressPromise:Promise[InetSocketAddress],
             unbindPromise: Promise[() ⇒ Future[Unit]],
             flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
             endpoint: InetSocketAddress,
             materializerSettings: MaterializerSettings) = Props(classOf[WebSocketListenStreamActor])
}
class WebSocketListenStreamActor extends Actor {
   def receive = ???
}


class WebSocketClientActor(uri:String) extends WebSocketClient(URI.create(uri)) with Actor with Stash {
   def onOpen(sh: ServerHandshake) = {
      self ! WebSocketMessage.ClientOpen(sh)
   }

   def onError(e: Exception) = {
      self ! WebSocketMessage.Error(e)
   }

   def onMessage(message: String) = {
      self ! WebSocketMessage.Message(message)
   }

   def onClose(i: Int, s: String, b: Boolean) = {
      self ! WebSocketMessage.Close(i, s, b)
   }

   def receive = awaitingListener


   var listener : ActorRef= _
   def awaitingListener : Receive = {
      case r @ WebSocket.Register(actor) =>
         listener = actor
         Future {
            blocking {
               val connected = connectBlocking()
               if(connected) {
                  self ! Connected(this.getConnection.getRemoteSocketAddress, this.getConnection.getLocalSocketAddress)

               } else {
                  actor ! CommandFailed(r)
                  self ! PoisonPill
               }

            }
         }
      case c : WebSocket.Connected =>
         context.become(running(listener))
         unstashAll()
      case _ => stash()
   }

   def running(listener :ActorRef) : Receive = {
      case e: WebSocket.Event =>
         listener ! e
      case c: WebSocket.Abort =>
         this.close()
      case c: WebSocket.Close =>
         this.close()
   }

}



class WebSocketStreamClientActor(processorPromise: Promise[Processor[String, String]],
                                 localAddressPromise: Promise[InetSocketAddress],
                                 val connectCmd: WebSocket.Connect, _settings: MaterializerSettings) extends Actor{
 // extends WebSocketClient(URI.create(connectCmd.remoteAddress.toString)) with WebSocketClientActor {

   val client = context.actorOf(Props(classOf[WebSocketClientActor], connectCmd.remoteAddress.toString))

   val primaryInputs: Inputs = new BatchingInputBuffer(10, writePump) {
      override def inputOnError(e: Throwable): Unit = fail(e)
   }
   val primaryOutputs = new SimpleOutputs(self, readPump)

   val initSteps = new SubReceive(waitingExposedProcessor)



   object tcpInputs extends DefaultInputTransferStates {
      private var closed: Boolean = false
      val receiveQueue = mutable.Queue[String]()

      val subreceive = new SubReceive(handleRead)


      def handleRead: Receive = {
         case WebSocketMessage.Message(message) ⇒
            receiveQueue += message
            readPump.pump()
         case WebSocketMessage.Close(code, reason, isRemote) ⇒
            closed = true
            webSocketOutputs.complete()
            writePump.pump()
            readPump.pump()
         case WebSocketMessage.Error(cause) ⇒ fail(new TcpStreamException(s"The connection closed with error $cause"))
      }

      override def inputsAvailable: Boolean = receiveQueue.nonEmpty
      override def inputsDepleted: Boolean = closed && !inputsAvailable
      override def isClosed: Boolean = closed

      override def cancel(): Unit = {
         closed = true
         receiveQueue.clear()
      }

      override def dequeueInputElement(): Any = {
         receiveQueue.dequeue()
      }

   }
   object webSocketOutputs extends DefaultOutputTransferStates {
      private var closed: Boolean = false
      private var pendingDemand = true

      val subreceive = new SubReceive(Actor.emptyBehavior)

      def handleWrite: Receive = {
         case WriteAck ⇒
            pendingDemand = true
            writePump.pump()
      }

      override def isClosed: Boolean = closed
      override def cancel(e: Throwable): Unit = {
         if (!closed && initialized)
         closed = true
      }
      override def complete(): Unit = {
         if (!closed && initialized) connection ! ConfirmedClose
         closed = true
      }
      override def enqueueOutputElement(elem: Any): Unit = {
         this
         pendingDemand = false
      }

      override def demandAvailable: Boolean = pendingDemand
   }

   object writePump extends Pump {

      def running = TransferPhase(primaryInputs.NeedsInput && webSocketOutputs.NeedsDemand) { () ⇒
         var batch = ByteString.empty
         while (primaryInputs.inputsAvailable) batch ++= primaryInputs.dequeueInputElement().asInstanceOf[ByteString]
         webSocketOutputs.enqueueOutputElement(batch)
      }

      override protected def pumpFinished(): Unit = {
         webSocketOutputs.complete()
         tryShutdown()
      }
      override protected def pumpFailed(e: Throwable): Unit = fail(e)
   }

   object readPump extends Pump {

      def running = TransferPhase(tcpInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
         primaryOutputs.enqueueOutputElement(tcpInputs.dequeueInputElement())
      }

      override protected def pumpFinished(): Unit = {
         tcpInputs.cancel()
         primaryOutputs.complete()
         tryShutdown()
      }
      override protected def pumpFailed(e: Throwable): Unit = fail(e)
   }






   import scala.concurrent._
   def waitingExposedProcessor: Receive = {
      case StreamWebSocketManager.ExposedProcessor(processor) ⇒


         initSteps.become(waitConnection(processor))
   }


   def waitConnection(exposedProcessor: Processor[String, String]): Receive = {
      case Connected(remoteAddress, localAddress) ⇒
         localAddressPromise.success(localAddress)
         processorPromise.success(exposedProcessor)
         initSteps.become(Actor.emptyBehavior)

      case f: CommandFailed ⇒
         val ex = new TcpStreamException("Connection failed.")
         localAddressPromise.failure(ex)
         processorPromise.failure(ex)
         fail(ex)
   }

   def receive = ???


   def activeReceive =
      primaryInputs.subreceive orElse primaryOutputs.subreceive orElse tcpInputs.subreceive orElse webSocketOutputs.subreceive

   readPump.nextPhase(readPump.running)
   writePump.nextPhase(writePump.running)

   def fail(e: Throwable): Unit = {
      tcpInputs.cancel()
      webSocketOutputs.cancel(e)
      primaryInputs.cancel()
      primaryOutputs.cancel(e)
   }

   def tryShutdown(): Unit = if (primaryInputs.isClosed && tcpInputs.isClosed && webSocketOutputs.isClosed) context.stop(self)



}