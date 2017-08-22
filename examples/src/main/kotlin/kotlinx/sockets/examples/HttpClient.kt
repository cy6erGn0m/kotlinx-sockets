package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import java.io.*
import java.net.*

fun main(args: Array<String>) {
    runBlocking {
        aSocket().tcp().connect(InetSocketAddress(InetAddress.getByName("google.com"), 80)).use { socket ->
            println("Connected")

            val output = socket.openWriteChannel()

            output.writeStringUtf8("GET / HTTP/1.1\r\n")
            output.writeStringUtf8("Host: google.com\r\n")
            output.writeStringUtf8("Accept: text/html\r\n")
            output.writeStringUtf8("Connection: close\r\n")
            output.writeStringUtf8("\r\n")
            output.flush()

            val bb = ByteBuffer.allocate(8192)
            val input = socket.openReadChannel()
            while (true) {
                bb.clear()
                if (input.readAvailable(bb) == -1) break

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

