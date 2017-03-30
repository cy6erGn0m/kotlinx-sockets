package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import org.junit.*
import java.net.*
import java.nio.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.properties.*
import kotlin.reflect.*
import kotlin.test.*

class ServerSocketTest {
    private val selector = ExplicitSelectorManager()
    private var client: Pair<Socket, Thread>? = null
    private var server by BlockingValue<AsyncServerSocket>()
    private var failure: Throwable? = null
    private val bound = CountDownLatch(1)

    @After
    fun tearDown() {
        client?.let { (s, t) ->
            s.close()
            t.interrupt()
        }

        selector.close()
        failure?.let { throw it }
    }

    @Test
    fun testBindAndAccept() {
        server {  }
        client {  }
    }

    @Test
    fun testRead() {
        server { client ->
            val bb = ByteBuffer.allocate(3)

            assertEquals(3, client.read(bb))
            assertEquals("123", String(bb.array()))
            bb.clear()

            assertEquals(-1, client.read(bb))
        }

        client { socket ->
            socket.getOutputStream().use { os ->
                os.write("123".toByteArray())
            }
        }
    }

    @Test
    fun testWrite() {
        server { client ->
            client.write(ByteBuffer.wrap("123".toByteArray()))
        }

        client { socket ->
            assertEquals("123", socket.getInputStream().reader().use { it.readText() })
        }
    }

    private fun server(block: suspend (AsyncSocket) -> Unit) {
        launch(CommonPool) {
            val server = aSocket(selector).tcp().bind(null)
            this@ServerSocketTest.server = server

            bound.countDown()

            loop@while (failure == null) {
                server.accept().use {
                    try {
                        block(it)
                    } catch (t: Throwable) {
                        addFailure(t)
                    }
                }
            }
        }
    }

    private fun client(block: (Socket) -> Unit) {
        bound.await()
        val client = Socket().apply { connect(server.localAddress) }

        val thread = thread(start = false) {
            try {
                client.use {
                    block(it)
                }
            } catch (t: Throwable) {
                addFailure(t)
            }
        }

        this.client = Pair(client, thread)
        thread.start()
        thread.join()
    }

    private fun addFailure(t: Throwable) {
        failure?.addSuppressed(t) ?: run { failure = t }
    }

    class BlockingValue<T> : ReadWriteProperty<Any?, T> {
        private var value: Any? = null
        private val latch = CountDownLatch(1)

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            latch.await()
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            require(latch.count == 1L)

            this.value = value
            latch.countDown()
        }
    }
}