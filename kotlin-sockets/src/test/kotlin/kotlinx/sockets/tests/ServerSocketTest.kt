package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import org.junit.*
import org.junit.rules.*
import java.nio.channels.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.properties.*
import kotlin.reflect.*
import kotlin.test.*

class ServerSocketTest {
    private var tearDown = false
    private val selector = ActorSelectorManager()
    private var client: Pair<java.net.Socket, Thread>? = null
    private var server by BlockingValue<ServerSocket>()
    private var failure: Throwable? = null
    private val bound = CountDownLatch(1)

    @get:Rule
    val timeout = Timeout(15L, TimeUnit.SECONDS)

    @After
    fun tearDown() {
        tearDown = true

        client?.let { (s, t) ->
            s.close()
            t.interrupt()
        }
        server.close()

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
            assertEquals("123", client.openReadChannel().readASCIILine())
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
            val channel = client.openWriteChannel(true)
            channel.writeStringUtf8("123")
        }

        client { socket ->
            assertEquals("123", socket.getInputStream().reader().use { it.readText() })
        }
    }

    private fun server(block: suspend (Socket) -> Unit) {
        launch(CommonPool) {
            try {
                val server = aSocket(selector).tcp().bind(null)
                this@ServerSocketTest.server = server

                bound.countDown()

                loop@ while (failure == null) {
                    server.accept().use {
                        try {
                            block(it)
                        } catch (t: Throwable) {
                            addFailure(t)
                        }
                    }
                }
            } catch (e: ClosedChannelException) {
            } catch (e: CancelledKeyException) {
            } catch (t: Throwable) {
                addFailure(t)
            }
        }
    }

    private fun client(block: (java.net.Socket) -> Unit) {
        bound.await()
        val client = java.net.Socket().apply { connect(server.localAddress) }

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

    inner class BlockingValue<T> : ReadWriteProperty<Any?, T> {
        private var value: Any? = null
        private val latch = CountDownLatch(1)

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (tearDown && latch.count != 0L) fail("value is not initialized")
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