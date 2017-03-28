package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*

fun main(args: Array<String>) {
    val pool = runUnlimitedPool()

    runBlocking(CommonPool) {
        SelectorManager().use { selector ->
            val (hosts, sockets) = selector.openConnector({ it: String -> InetSocketAddress(it, 80) }, { a, s -> Pair(a, s) })
            hosts.send("google.com")
            hosts.close()

            while (true) {
                val (host, socket) = sockets.receiveOrNull() ?: break

                val out = socket.openTextSendChannel(Charsets.ISO_8859_1, pool)
                val input = socket.openReceiveChannel(pool)

                out.send("GET / HTTP/1.1\r\n")
                out.send("Host: $host\r\n")
                out.send("Connection: close\r\n")
                out.send("\r\n")

                input.consumeEach { bb ->
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
                    System.out.flush()
                    pool.send(bb)
                }

                println("All consumed for $host")
            }
        }
    }
}

fun runUnlimitedPool(): Channel<ByteBuffer> {
    val pool = ArrayChannel<ByteBuffer>(256)

    launch(CommonPool) {
        while (true) {
            if (!pool.offer(ByteBuffer.allocate(8192))) break
        }
    }

    return pool
}
