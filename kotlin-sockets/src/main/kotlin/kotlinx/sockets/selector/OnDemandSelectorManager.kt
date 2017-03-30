package kotlinx.sockets.selector

import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.nio.channels.spi.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class OnDemandSelectorManager : Closeable, SelectorManager {
    override val provider: SelectorProvider = SelectorProvider.provider()

    @Volatile
    private var closed = false
    private val interestQueue = ArrayBlockingQueue<AsyncSelectable>(1000)

    override fun close() {
        closed = true

        withSelector(false) {
            interestQueue.put(Poison)
            wakeup()
        }
    }

    override fun notifyInterest(s: AsyncSelectable) {
        interestQueue.put(s)
        withSelector { wakeup() }
    }

    override fun notifyInterestDirect(s: AsyncSelectable) {
        require(Thread.currentThread().threadGroup === selectorsGroup)

        pushInterestImpl(s)
    }

    override fun notifyClosed(s: AsyncSelectable) {
        withSelector(false) {
            s.channel.keyFor(this)?.attach(null)
            this.wakeup()
        }
    }

    private fun pushInterestImpl(s: AsyncSelectable) {
        if (s.interestedOps != 0) {
            withSelector {
                pushInterestImpl(this, s)
            }
        }
    }

    private fun pushInterestImpl(selector: Selector, s: AsyncSelectable) {
        if (s !== Poison) {
            try {
                s.channel.keyFor(selector)?.interestOps(s.interestedOps) ?: selector.assignKey(s)
            } catch (e: ClosedChannelException) {
                s.channel.keyFor(selector)?.apply {
                    cancel()
                    attach(null)
                }
            } catch (e: CancelledKeyException) {
            }
        }
    }


    private fun Selector.assignKey(s: AsyncSelectable) {
        s.channel.register(this, s.interestedOps, s)
    }

    private val currentSelector = AtomicReference<Selector?>()
    private fun selectorMain(block: (Selector.() -> Unit)?) {
        var evaluated = false
        require(Thread.currentThread().threadGroup === selectorsGroup)

        provider.openSelector().use { selector ->
            while (!closed && currentSelector.compareAndSet(null, selector)) {
                try {
                    if (!evaluated) {
                        block?.invoke(selector)
                        evaluated = true
                    }

                    do {
                        loop(selector)
                    } while (interestQueue.isNotEmpty() && !closed)
                } finally {
                    currentSelector.set(null)
                    if (interestQueue.isEmpty()) {
                        break
                    }
                }
            }
        }

        if (!closed && !evaluated && block != null) {
            withSelector(true, block)
        }
    }

    private fun loop(selector: Selector) {
        processInterestQueue(selector)

        while (!closed) {
            if (!selector.isOpen) return

            val preselected = selector.selectNow()
            val hasInterestToProcess = interestQueue.isNotEmpty()

            if (preselected == 0 && selector.keys().isEmpty() && !hasInterestToProcess && !closed) {
                val e = interestQueue.poll(5L, TimeUnit.SECONDS) ?: return
                pushInterestImpl(selector, e)
                processInterestQueue(selector)
            } else {
                if (preselected > 0 || hasInterestToProcess || selector.select() > 0) {
                    selector.selectedKeys().forEach {
                        dispatchSelectedKey(it)
                    }
                    selector.selectedKeys().clear()
                }

                processInterestQueue(selector)
            }
        }
    }

    private fun processInterestQueue(selector: Selector) {
        while (!closed) {
            val e = interestQueue.poll() ?: break
            pushInterestImpl(selector, e)
        }
    }

    private fun dispatchSelectedKey(key: SelectionKey) {
        try {
            key.interestOps(0)
        } catch (expected: CancelledKeyException) {
            return
        }

        val subj = key.attachment() as? AsyncSelectable

        try {
            subj?.onSelected(key) ?: key.cancel()
        } catch (t: Throwable) {
            try {
                subj?.onSelectionFailed(t) ?: t.printStackTrace()
            } catch (t2: Throwable) {
                t2.printStackTrace()
            } finally {
                key.cancel()
            }
        }
    }

    private fun withSelector(forceStart: Boolean = true, block: Selector.() -> Unit) {
        val existing = currentSelector.get()
        if (existing != null) {
            block(existing)
        } else if (forceStart) {
            start(block)
        }
    }

    private fun start(block: (Selector.() -> Unit)?) {
        if (currentSelector.get() == null) {
            selectorsCoroutineDispatcher.dispatch(selectorsCoroutineDispatcher, Runnable { selectorMain(block) })
        }
    }

    companion object {
        private val Poison = object : AsyncSelectable {
            override val channel: SelectableChannel
                get() = throw UnsupportedOperationException()

            override val interestedOps: Int
                get() = 0

            override fun onSelected(key: SelectionKey) {
            }

            override fun onSelectionFailed(t: Throwable) {
            }
        }
    }
}
