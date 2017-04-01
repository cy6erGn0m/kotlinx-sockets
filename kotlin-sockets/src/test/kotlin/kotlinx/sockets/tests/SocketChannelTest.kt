package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.*
import kotlinx.sockets.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.selector.*
import org.junit.*
import org.junit.rules.*
import java.nio.*
import java.util.concurrent.*
import kotlin.test.*

class SocketChannelTest {
    private val selector = ExplicitSelectorManager()

    private lateinit var pool: Channel<ByteBuffer>
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverAccept: ProducerJob<Socket>

    @get:Rule
    val timeout = Timeout(15L, TimeUnit.SECONDS)

    @Before
    fun setUp() {
        pool = runDefaultByteBufferPool()

        serverSocket = aSocket(selector).tcp().bind(null)
        serverAccept = serverSocket.openAcceptChannel()
    }

    @After
    fun tearDown() {
        pool.close()

        serverSocket.close()
        selector.close()

        serverAccept.cancel()
    }

    @Test
    fun accept() {
        runBlocking {
            aSocket(selector).tcp().connect(serverSocket.localAddress).use { }

            val client = serverAccept.receive()
            client.close()
        }
    }

    @Test
    fun readAndWrite() {
        val server = launch(CommonPool) {
            serverAccept.receive().use { client ->
                val bb = ByteBuffer.allocate(10)

                assertEquals(3, client.read(bb))
                bb.flip()

                for (i in bb.position() .. bb.limit() - 1) {
                    bb.put(i, (bb.get(i) + 1).toByte())
                }

                client.write(bb)
            }
        }

        runBlocking {
            try {
                aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                    val receive = socket.openReceiveChannel(pool)
                    val send = socket.openSendChannel(pool)

                    try {
                        send.send(ByteBuffer.wrap("123".toByteArray()))

                        val b = receive.receive()
                        assertEquals(3, b.remaining())
                        assertEquals("234", String(b.array(), b.arrayOffset() + b.position(), b.remaining()))
                    } finally {
                        send.close()
                    }
                }
            } finally {
                server.join()
                server.invokeOnCompletion { it?.let { throw it } }
            }
        }
    }

    @Test
    fun textWrite() {
        val server = launch(CommonPool) {
            serverAccept.receive().use { client ->
                val bb = ByteBuffer.allocate(10)

                assertEquals(3, client.read(bb))
                bb.flip()

                for (i in bb.position() .. bb.limit() - 1) {
                    bb.put(i, (bb.get(i) + 1).toByte())
                }

                client.write(bb)
            }
        }

        runBlocking {
            try {
                aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                    val receive = socket.openReceiveChannel(pool)
                    val send = socket.openTextSendChannel(Charsets.ISO_8859_1, pool)

                    try {
                        send.send("123")

                        val b = receive.receive()
                        assertEquals(3, b.remaining())
                        assertEquals("234", String(b.array(), b.arrayOffset() + b.position(), b.remaining()))
                    } finally {
                        send.close()
                    }
                }
            } finally {
                server.join()
            }
        }
    }

    @Test
    fun testTextReceive() {
        val clientJob = launch(CommonPool) {
            aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                val input = socket.openTextReceiveChannel(Charsets.ISO_8859_1, pool)
                val output = socket.openTextSendChannel(Charsets.ISO_8859_1, pool)

                try {
                    output.send("abc")
                    val text = input.receive()
                    assertEquals("ABC", text)
                } finally {
                    output.close()
                }
            }
        }

        val server = launch(CommonPool) {
            serverAccept.receive().use { client ->
                val input = client.openTextReceiveChannel(Charsets.ISO_8859_1, pool)
                val output = client.openTextSendChannel(Charsets.ISO_8859_1, pool)

                try {
                    val text = input.receive()
                    output.send(text.toUpperCase())
                } finally {
                    output.close()
                }

                clientJob.join()
            }
        }

        runBlocking {
            clientJob.join()
            clientJob.invokeOnCompletion { it?.let { throw it } }
            server.join()
            server.invokeOnCompletion { it?.let { throw it } }
        }
    }

    @Test
    fun testLinesReceive() {
        val clientJob = launch(CommonPool) {
            aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                val input = socket.openLinesReceiveChannel(Charsets.ISO_8859_1, pool)
                val output = socket.openTextSendChannel(Charsets.ISO_8859_1, pool)

                try {
                    output.send("abc")
                    output.send("\ndef\n123\n")

                    assertEquals("ABC", input.receiveOrNull())
                    assertEquals("DEF", input.receiveOrNull())
                    assertEquals("123", input.receiveOrNull())
                } finally {
                    output.close()
                }
            }
        }

        val server = launch(CommonPool) {
            serverAccept.receive().use { client ->
                val input = client.openLinesReceiveChannel(Charsets.ISO_8859_1, pool)
                val output = client.openTextSendChannel(Charsets.ISO_8859_1, pool)

                try {
                    while (true) {
                        val line = select<String?> {
                            input.onReceiveOrNull { it }
                            clientJob.onJoin { null }
                        } ?: break

                        output.send(line.toUpperCase() + "\n")
                    }
                } finally {
                    output.close()
                }
            }
        }

        runBlocking {
            clientJob.join()
            server.join()

            server.invokeOnCompletion { it?.let { throw it } }
            clientJob.invokeOnCompletion { it?.let { throw it } }
        }
    }
}
