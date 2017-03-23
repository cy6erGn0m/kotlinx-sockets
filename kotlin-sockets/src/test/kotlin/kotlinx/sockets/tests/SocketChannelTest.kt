package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import org.junit.*
import java.nio.*
import kotlin.test.*

class SocketChannelTest {
    private val selector = SelectorManager()

    private lateinit var pool: Channel<ByteBuffer>
    private lateinit var serverSocket: AsyncServerSocket
    private lateinit var serverAccept: ProducerJob<AsyncSocket>

    @Before
    fun setUp() {
        pool = runDefaultByteBufferPool()

        serverSocket = selector.serverSocket()
        serverSocket.bind(null)

        serverAccept = serverSocket.openAcceptChannel()
    }

    @After
    fun tearDown() {
        pool.close()

        serverAccept.cancel()
        serverSocket.close()
        selector.close()
    }

    @Test
    fun accept() {
        runBlocking {
            selector.socket().use { it.connect(serverSocket.localAddress) }
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
                selector.socket().use { socket ->
                    socket.connect(serverSocket.localAddress)

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
                selector.socket().use { socket ->
                    socket.connect(serverSocket.localAddress)

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
}
