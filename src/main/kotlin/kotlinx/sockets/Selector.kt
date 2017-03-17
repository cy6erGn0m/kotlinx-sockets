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

class SelectorManager(val dispatcher: CoroutineDispatcher = pool.asCoroutineDispatcher()): AutoCloseable, Closeable {
    private val selector = Selector.open()
    private val q = ArrayChannel<(Selector) -> Unit>(1000)

    fun start() {
        launch(dispatcher) {
            selectorLoop(selector, q) { key ->
                key.interestOps(0)

                launch(dispatcher) {
                    (key.attachment() as? AsyncSelectable)?.apply {
                        onSelected(key)
                        if (key.interestOps() != interestedOps) {
                            registerSafe(this)
                        }
                    }
                }
            }
        }
    }

    fun socket(): AsyncSocket<SocketChannel> {
        return AsyncSocket(selector.provider().openSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    suspend fun registerSafe(selectable: AsyncSelectable) {
        q.send({ selector ->
            registerUnsafe(selectable, selector)
        })
        selector.wakeup()
    }

    override fun close() {
        selector.close()
    }

    private fun registerUnsafe(selectable: AsyncSelectable, selector: Selector) {
        selectable.channel.keyFor(selector)?.also { key -> key.interestOps(selectable.interestedOps) } ?: selectable.channel.register(selector, selectable.interestedOps).also { key -> key.attach(selectable) }
    }
}

