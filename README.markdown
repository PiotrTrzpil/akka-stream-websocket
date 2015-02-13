## Akka Streams WebSockets

A bridge from WebSockets into Akka Streams based on the `https://github.com/wandoulabs/spray-websocket` project.
A really simple implementation, seems to be kind of working. But:

1. No backpressure. The websocket protocol does not define it, although it might be possible on the tcp level. Another option is to implement a custom protocol for backpressure on top and encode every user-level frame. A specialized javascript client will be needed then however.
2. No routing path support yet.
3. Error handling to be improved.

A server example:

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
        
A client example:

        val client = system.actorOf(WebSocketClient.props(), "websocket-client")
        (client ? WebSocketMessage.Connect("localhost", 8080, "/")).map {
           case WebSocketMessage.Connection(inbound, outbound) =>
              Sink(outbound).runWith(
                 Source(200 milli, 200 milli, () => TextFrame("hi")))
              Source(inbound).foreach { case TextFrame(text) =>
                 println(text.utf8String)
              }
        }
