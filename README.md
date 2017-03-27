# kotlinx sockets (🥚🥚🥚 incubating)

Kotlinx.sockets is a library to bring rich coroutines experience to NIO sockets, eliminate terrible callbacks and selector loops and related difficult code.
  
With the library and kotlin coroutines you can simply write async NIO code in usual synchronous style.
 
Consider example ([full source](examples/src/main/kotlin/kotlinx/sockets/examples/HttpClient.kt))
  
```kotlin
fun main(args: Array<String>) {
    runBlocking { // start coroutines
        SelectorManager().use { manager ->
            manager.socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("google.com"), 80))
                println("Connected") // now we are connected

                // chain of async write
                socket.send("GET / HTTP/1.1\r\n")
                socket.send("Host: google.com\r\n")
                socket.send("Accept: text/html\r\n")
                socket.send("Connection: close\r\n")
                socket.send("\r\n")

                // loop to read bytes and write to the console
                val bb = ByteBuffer.allocate(8192)
                while (true) {
                    bb.clear()
                    if (socket.read(bb) == -1) break // async read

                    bb.flip()
                    System.out.write(bb)
                    System.out.flush()
                }

                println()
            }
        }
    }
}
```

### Examples

 - [socket echo](examples/src/main/kotlin/kotlinx/sockets/examples/Echo.kt)
 - [net shell](examples/src/main/kotlin/kotlinx/sockets/examples/NetShell.kt)
 - [http request](examples/src/main/kotlin/kotlinx/sockets/examples/HttpClient.kt)
 - [http server](examples/src/main/kotlin/kotlinx/sockets/examples/HttpServer.kt)
 - [numbers client and server](examples/src/main/kotlin/kotlinx/sockets/examples/numbers)
 - [coroutine channels](examples/src/main/kotlin/kotlinx/sockets/examples/CoroutineChannels.kt)
 - [DNS client](examples/src/main/kotlin/kotlinx/sockets/examples/dns)

