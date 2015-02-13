## Akka Streams WebSockets

Bridging WebSockets into Akka Streams based on the `https://github.com/wandoulabs/spray-websocket` project

A server:

        val server = system.actorOf(WebSocketServer.props(), "websocket-server")
        (server ? WebSocketMessage.Bind("localhost", 8080)).map {
           case Bound(addr, connections) =>
              Source(connections).foreach {
                 case WebSocketMessage.Connection(inbound, outbound) =>
                    Source(inbound).map { case TextFrame(text) =>
                       TextFrame(text.utf8String.toUpperCase)
                    }.runWith(Sink(outbound))
              }
        }
        
A client:

        val client = system.actorOf(WebSocketClient.props(), "websocket-client")
        (client ? WebSocketMessage.Connect("localhost", 8080, "/")).map {
           case WebSocketMessage.Connection(inbound, outbound) =>
              Sink(outbound).runWith(
                 Source(200 milli, 200 milli, () => TextFrame("hi")))
              Source(inbound).foreach { case TextFrame(text) =>
                 println(text.utf8String)
              }
        }
