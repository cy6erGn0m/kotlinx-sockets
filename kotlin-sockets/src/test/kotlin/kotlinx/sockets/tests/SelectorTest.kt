package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.selector.*
import org.junit.*
import java.net.*
import java.nio.*

class SelectorTest {
    @Test
    @Ignore
    fun name() {
        val pool = runUnlimitedPool()

        runBlocking(CommonPool) {
            SelectorManager().use { selector ->
                selector.socket().use { before ->
                    val socket = before.connect(InetSocketAddress("google.com", 80))

                    val out = ArrayChannel<String>(10)
                    val input = ArrayChannel<String>(2)

                    socket.receiveTextTo(input, Charsets.ISO_8859_1, pool).apply {
                        invokeOnCompletion { input.close(it) }
                        start()
                    }
                    socket.sendTextFrom(out, Charsets.ISO_8859_1, pool).start()

                    out.send("GET / HTTP/1.1\r\n")
                    out.send("Host: www.google.com\r\n")
                    out.send("Connection: close\r\n")
                    out.send("\r\n")

                    input.consumeEach {
                        println(it)
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
}
