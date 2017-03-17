package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.net.*
import java.nio.*

fun main(args: Array<String>) {
    runBlocking {
        SelectorManager().use { manager ->
            manager.start()

            manager.socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("google.com"), 80))
                println("Connected")

                socket.send("GET / HTTP/1.1\r\n")
                socket.send("Host: google.com\r\n")
                socket.send("Accept: text/html\r\n")
                socket.send("Connection: close\r\n")
                socket.send("\r\n")

                val bb = ByteBuffer.allocate(8192)
                while (true) {
                    bb.clear()
                    val rc = socket.read(bb)

                    if (rc == -1) break

                    bb.flip()
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
                    System.out.flush()
                }

                println()
            }
        }
    }
}

private suspend fun AsyncSocket<*>.send(text: String) {
    write(ByteBuffer.wrap(text.toByteArray()))
}
