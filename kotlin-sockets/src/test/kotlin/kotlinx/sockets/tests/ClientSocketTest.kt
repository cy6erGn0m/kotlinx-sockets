package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import org.junit.*
import java.net.*
import java.nio.*
import kotlin.concurrent.*
import kotlin.test.*

class ClientSocketTest {
    private val selector = SelectorManager()
    private var serverError: Throwable? = null
    private var server: Pair<ServerSocket, Thread>? = null

    @After
    fun tearDown() {
        selector.close()
        server?.let { (server, thread) ->
            server.close()
            thread.interrupt()
        }

        serverError?.let { throw it }
    }

    @Test
    fun testConnect() {
        server(Socket::close)

        client {
        }
    }

    @Test
    fun testRead() {
        server { client ->
            client.getOutputStream().use { o ->
                o.write("123".toByteArray())
                o.flush()
            }
        }

        client { socket ->
            val bb = ByteBuffer.allocate(3)

            while (bb.hasRemaining()) {
                if (socket.read(bb) == -1) break
            }

            assertEquals("123", String(bb.array()))
        }
    }

    @Test
    fun testWrite() {
        server { client ->
            assertEquals("123", client.getInputStream().reader().readText())
        }

        client { socket ->
            val bb = ByteBuffer.allocate(3)
            bb.put("123".toByteArray())
            bb.flip()

            while (bb.hasRemaining()) {
                socket.write(bb)
            }
        }
    }

    @Test
    fun testReadParts() {
        server { client ->
            client.getOutputStream().use { o ->
                o.write("0123456789".toByteArray())
                o.flush()
            }
        }

        client { socket ->
            val bb = ByteBuffer.allocate(3)
            val sb = StringBuilder()

            while (true) {
                bb.clear()
                if (socket.read(bb) == -1) break
                bb.flip()

                sb.append(String(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining()))
            }

            assertEquals("0123456789", sb.toString())
        }
    }

    @Test
    fun testWriteParts() {
        server { client ->
            assertEquals("0123456789", client.getInputStream().reader().readText())
        }

        client { socket ->
            val bb = ByteBuffer.allocate(1)

            for (i in 0..9) {
                bb.put(0, i.toString()[0].toByte()).clear()
                socket.write(bb)
            }
        }
    }

    private fun client(block: suspend (AsyncSocket) -> Unit) {
        runBlocking {
            selector.socket().use {
                block(it.connect(server!!.first.localSocketAddress))
            }
        }
    }

    private fun server(block: (Socket) -> Unit) {
        val server = ServerSocket(0)
        val thread = thread(start = false) {
            while (true) {
                val client = try { server.accept() } catch (t: Throwable) { break }

                try {
                    client.use(block)
                } catch (t: Throwable) {
                    serverError?.addSuppressed(t) ?: run { serverError = t }
                }
            }
        }

        this.server = Pair(server, thread)
        thread.start()
    }
}