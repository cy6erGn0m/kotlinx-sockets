package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

/**
 * Represents a coroutine facade for NIO selector and socket factory. Need to be closed to release resources.
 */
class SelectorManager(dispatcher: CoroutineContext = ioCoroutineDispatcher) : AutoCloseable, Closeable {
    @Volatile
    private var closed = false
    private val selector = lazy { if (closed) throw ClosedSelectorException(); Selector.open()!! }
    private val q = ArrayBlockingQueue<AsyncSelectable>(1000)

    private val selectorJob = launch(dispatcher, false) {
        try {
            selectorLoop(selector.value)
        } catch (expected: ClosedSelectorException) {
        }
    }.apply {
        invokeOnCompletion {
            selector.value.close()
        }
    }

    /**
     * Creates TCP socket that not yet connected
     */
    fun socket(): AsyncSocket {
        ensureStarted()
        return AsyncSocketImpl(selector.value.provider().openSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    /**
     * Opens socket, configures it by [configure] function (optional) and connects it to [address], suspends until connection
     * completed (possibly failed).
     */
    suspend fun socket(address: SocketAddress, configure: AsyncSocket.() -> Unit = {}): AsyncSocket {
        return socket().apply { configure(); connect(address) }
    }

    /**
     * Creates TCP server socket that not yet bound.
     */
    fun serverSocket(): AsyncServerSocket {
        ensureStarted()
        return AsyncServerSocketImpl(selector.value.provider().openServerSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    /**
     * Closes instance, releases all resources. All sockets that were created by this instance becomes illegal
     * and should be closed as well.
     */
    override fun close() {
        closed = true
        if (selector.isInitialized()) {
            // due to bug in JDK we should never close selector outside of the selector loop
            // this is why we have to set flag, signal selector to wakeup and wait until
            // the selector loop completion
            selector.value.apply {
                wakeup()
                if (!selectorJob.isCompleted) {
                    runBlocking {
                        selectorJob.join()
                    }
                }
                close()
            }
        }
    }

    internal fun registerSafe(selectable: AsyncSelectable) {
        q.put(selectable)
        selector.value.wakeup()
    }

    private tailrec fun selectorLoop(selector: Selector) {
        if (!closed && selector.select() > 0) {
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()

                handleKey(key)
            }
        }

        if (closed) return

        while (!closed) {
            val selectable = q.poll() ?: break
            handleRegister(selectable)
        }

        selectorLoop(selector)
    }

    private fun handleKey(key: SelectionKey) {
        try {
            key.interestOps(0)

            handleSelectedKey(key, null)
        } catch (t: Throwable) { // key cancelled or rejected execution
            try {
                handleSelectedKey(key, t)
            } catch (t2: Throwable) {
                t.printStackTrace()
                t2.printStackTrace()
            }
        }
    }

    private fun ensureStarted() {
        if (closed) throw ClosedSelectorException()
        selectorJob.start()
    }

    private fun handleSelectedKey(key: SelectionKey, t: Throwable?) {
        (key.attachment() as? AsyncSelectable)?.apply {
            if (t != null) {
                onSelectionFailed(t)
            } else {
                try {
                    onSelected(key)
                } catch (t: CancelledKeyException) {
                    key.attach(null)
                    onSelectionFailed(t)
                }
            }
        }
    }

    private fun handleRegister(selectable: AsyncSelectable) {
        try {
            registerUnsafe(selectable)
        } catch (c: ClosedChannelException) {
        } catch (c: CancelledKeyException) {
        }
    }

    private fun registerUnsafe(selectable: AsyncSelectable) {
        val requiredOps = selectable.interestedOps

        selectable.channel.keyFor(selector.value)?.also { key -> if (key.interestOps() != requiredOps) key.interestOps(selectable.interestedOps) }
                ?: selectable.channel.register(selector.value, requiredOps).also { key -> key.attach(selectable) }
    }
}

