package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.selector.*
import org.junit.*
import org.junit.rules.*
import java.util.concurrent.*
import kotlin.test.*

class SocketChannelTest {
    private val selector = ActorSelectorManager()

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
                val input = client.openReadChannel()
                val out = client.openWriteChannel(true)

                val buffer = ByteBuffer.allocate(512)
                while (true) {
                    buffer.clear()
                    val rc = input.readAvailable(buffer)
                    if (rc == -1) break
                    buffer.flip()

                    for (i in buffer.position() until buffer.limit()) {
                        val b = buffer.get(i).toInt() and 0xff
                        val n = if (b == 0x0d || b == 0x0a) b else b + 1
                        buffer.put(i, n.toByte())
                    }

                    out.writeFully(buffer)
                }
            }
        }

        runBlocking {
            try {
                aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(true)

                    try {
                        output.writeStringUtf8("123\n")
                        assertEquals("234", input.readASCIILine())
                    } finally {
                        output.close()
                    }
                }
            } finally {
                server.joinOrFail()
            }
        }
    }

    @Test
    fun connectionMultiple() = runBlocking {
        val server = launch(ioCoroutineDispatcher) {
            serverAccept.consumeEach { c ->
                delay(1)
                c.close()
            }
        }

        try {
            for (i in 1..50) {
                aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                    delay(1)
                    socket.close()
                }
            }
        } finally {
            serverAccept.cancel()
            server.joinOrFail()
            serverSocket.close()
        }
    }

    @Test
    fun textWrite() {
        val server = launch(CommonPool) {
            serverAccept.receive().use { client ->
                val input = client.openReadChannel()
                val out = client.openWriteChannel(true)

                val buffer = ByteBuffer.allocate(512)
                while (true) {
                    buffer.clear()
                    val rc = input.readAvailable(buffer)
                    if (rc == -1) break
                    buffer.flip()

                    for (i in buffer.position() until buffer.limit()) {
                        val b = buffer.get(i).toInt() and 0xff
                        val n = if (b == 0x0d || b == 0x0a) b else b + 1
                        buffer.put(i, n.toByte())
                    }

                    out.writeFully(buffer)
                }
            }
        }

        runBlocking {
            try {
                aSocket(selector).tcp().connect(serverSocket.localAddress).use { socket ->
                    val receive = socket.openReadChannel()
                    val send = socket.openWriteChannel(true)

                    try {
                        send.writeStringUtf8("123\n")
                        assertEquals("234", receive.readASCIILine())
                    } finally {
                        send.close()
                    }
                }
            } finally {
                server.joinOrFail()
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
            clientJob.joinOrFail()
            server.joinOrFail()
        }
    }
}
