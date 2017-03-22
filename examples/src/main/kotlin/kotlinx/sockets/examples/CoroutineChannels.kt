package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import java.net.*
import java.nio.*

fun main(args: Array<String>) {
    val pool = runUnlimitedPool()

    runBlocking(CommonPool) {
        SelectorManager().use { selector ->
            selector.socket().use { socket ->
                socket.connect(InetSocketAddress("google.com", 80))

                val out = ArrayChannel<String>(10)
                val input = ArrayChannel<ByteBuffer>(2)

                socket.receiveTo(input, pool).start()
                socket.sendFrom(out, Charsets.ISO_8859_1, pool).start()

                out.send("GET / HTTP/1.1\r\n")
                out.send("Host: www.google.com\r\n")
                out.send("Connection: close\r\n")
                out.send("\r\n")

                input.consumeEach { bb ->
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
                    System.out.flush()
                    pool.send(bb)
                }

                println("All consumed")
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
