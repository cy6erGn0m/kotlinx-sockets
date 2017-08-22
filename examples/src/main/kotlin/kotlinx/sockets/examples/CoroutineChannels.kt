package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.adapters.*
import java.net.*

fun main(args: Array<String>) {
    runBlocking(CommonPool) {
        val (hosts, sockets) = openConnector({ it: String -> InetSocketAddress(it, 80) }, { a, s -> Pair(a, s) })
        hosts.send("google.com")
        hosts.close()

        while (true) {
            val (host, socket) = sockets.receiveOrNull() ?: break

            val out = socket.openWriteChannel(false)
            val input = socket.openReadChannel()

            out.writeStringUtf8("GET / HTTP/1.1\r\n")
            out.writeStringUtf8("Host: $host\r\n")
            out.writeStringUtf8("Connection: close\r\n")
            out.writeStringUtf8("\r\n")
            out.flush()

            val bb = ByteBuffer.allocate(8192)
            while (true) {
                bb.clear()
                val rc = input.readAvailable(bb)
                if (rc == -1) break

                System.out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
                System.out.flush()
            }

            println("All consumed for $host")
        }
    }
}
