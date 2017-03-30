package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.nio.channels.spi.*
import java.util.concurrent.*

/**
 * A selector manager that creates selector and loop thread lazily and only disposes it on [close].
 */
class ExplicitSelectorManager : Closeable, SelectorManager, DisposableHandle {
    @Volatile
    private var closed = false

    private val selector = lazy { if (closed) throw ClosedSelectorException(); ensureStarted(); Selector.open()!! }
    private val q = ArrayBlockingQueue<AsyncSelectable>(1000)

    override val provider: SelectorProvider = SelectorProvider.provider()

    private val selectorJob = launch(selectorsCoroutineDispatcher, false) {
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

    override fun dispose() {
        close()
    }

    override fun notifyInterest(s: AsyncSelectable) {
        q.put(s)
        selector.value.wakeup()
    }

    override fun notifyClosed(s: AsyncSelectable) {
        if (selector.isInitialized()) {
            selector.value.wakeup()
        }
    }

    override fun notifyInterestDirect(s: AsyncSelectable) {
        require(Thread.currentThread().threadGroup === selectorsGroup)

        registerUnsafe(s)
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

        selectable.channel.keyFor(selector.value)?.also { key ->
            when {
                !key.isValid -> key.cancel()
                key.interestOps() != requiredOps -> key.interestOps(selectable.interestedOps)
            }
        } ?: selectable.channel.register(selector.value, requiredOps).also { key -> key.attach(selectable) }
    }
}

