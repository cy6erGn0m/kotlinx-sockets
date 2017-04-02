package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import org.junit.*
import org.junit.rules.*
import java.net.ServerSocket
import java.nio.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.test.*

class ClientSocketTest {
    private val selector = ExplicitSelectorManager()
    private var serverError: Throwable? = null
    private var server: Pair<ServerSocket, Thread>? = null

    @get:Rule
    val timeout = Timeout(15L, TimeUnit.SECONDS)

    @After
    fun tearDown() {
        server?.let { (server, thread) ->
            server.close()
            thread.interrupt()
        }
        selector.close()

        serverError?.let { throw it }
    }

    @Test
    fun testConnect() {
        server { it.close() }

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

    private fun client(block: suspend (Socket) -> Unit) {
        runBlocking {
            aSocket(selector).tcp().connect(server!!.first.localSocketAddress).use {
                block(it)
            }
        }
    }

    private fun server(block: (java.net.Socket) -> Unit) {
        val server = java.net.ServerSocket(0)
        val thread = thread(start = false) {
            try {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (t: Throwable) {
                        break
                    }

                    client.use(block)
                }
            } catch (t: Throwable) {
                serverError?.addSuppressed(t) ?: run { serverError = t }
            }
        }

        this.server = Pair(server, thread)
        thread.start()
    }
}