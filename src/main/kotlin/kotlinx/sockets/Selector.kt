package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel
import java.io.*
import java.nio.channels.*

private tailrec fun selectorLoop(selector: Selector, q: Channel<(Selector) -> Unit>, consumer: (SelectionKey) -> Unit) {
    selector.select()

    while (true) {
        val work = q.poll() ?: break
        work(selector)
    }

    val keys = selector.selectedKeys().iterator()
    while (keys.hasNext()) {
        val key = keys.next()
        keys.remove()

        consumer(key)
    }

    selectorLoop(selector, q, consumer)
}

class SelectorManager(val dispatcher: CoroutineDispatcher = ioPool.asCoroutineDispatcher()): AutoCloseable, Closeable {
    @Volatile
    private var closed = false
    private val selector = lazy { if (closed) throw ClosedSelectorException(); Selector.open() }
    private val q = ArrayChannel<(Selector) -> Unit>(1000)

    private val selectorJob = launch(dispatcher, false) {
        launch(dispatcher, false) {
            selectorLoop(selector.value, q) { key ->
                key.interestOps(0)

                launch(dispatcher) {
                    handleSelectedKey(key)
                }
            }
        }
    }

    fun socket(): AsyncSocket {
        ensure()
        return AsyncSocketImpl(selector.value.provider().openSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    override fun close() {
        closed = true
        if (selector.isInitialized()) selector.value.close()
    }

    internal suspend fun registerSafe(selectable: AsyncSelectable) {
        q.send({ selector ->
            registerUnsafe(selectable, selector)
        })
        selector.value.wakeup()
    }

    private fun ensure() {
        if (closed) throw ClosedSelectorException()
        selectorJob.start()
    }

    private suspend fun handleSelectedKey(key: SelectionKey) {
        (key.attachment() as? AsyncSelectable)?.apply {
            onSelected(key)
            if (key.interestOps() != interestedOps) {
                registerSafe(this)
            }
        }
    }

    private fun registerUnsafe(selectable: AsyncSelectable, selector: Selector) {
        selectable.channel.keyFor(selector)?.also { key -> key.interestOps(selectable.interestedOps) } ?: selectable.channel.register(selector, selectable.interestedOps).also { key -> key.attach(selectable) }
    }
}

