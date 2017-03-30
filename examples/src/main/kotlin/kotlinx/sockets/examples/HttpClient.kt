package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import java.io.*
import java.net.*
import java.nio.*

fun main(args: Array<String>) {
    runBlocking {
        aSocket().tcp().connect(InetSocketAddress(InetAddress.getByName("google.com"), 80)).use { socket ->
            println("Connected")

            socket.send("GET / HTTP/1.1\r\n")
            socket.send("Host: google.com\r\n")
            socket.send("Accept: text/html\r\n")
            socket.send("Connection: close\r\n")
            socket.send("\r\n")

            val bb = ByteBuffer.allocate(8192)
            while (true) {
                bb.clear()
                if (socket.read(bb) == -1) break

                bb.flip()
                System.out.write(bb)
                System.out.flush()
            }

            println()
        }
    }
}

private fun PrintStream.write(bb: ByteBuffer) {
    require(bb.hasArray())

    write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
    bb.position(bb.limit())
}

private suspend fun AsyncSocket.send(text: String) {
    write(ByteBuffer.wrap(text.toByteArray()))
}
