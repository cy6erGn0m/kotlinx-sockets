package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * A selector manager that creates a selector instance and run selection loop on-demand only.
 * Will automatically dispose selector instance and stop loop on idle of time specified by [idleTime] [idleTimeUnit] (5 seconds by default).
 *
 * Once closed auto-start will not work anymore.
 */
class OnDemandSelectorManager(val idleTime: Long = 5, val idleTimeUnit: TimeUnit = TimeUnit.SECONDS) : Closeable, SelectorManagerSupport() {
    @Volatile
    private var closed = false
    private val interestQueue = ArrayBlockingQueue<AsyncSelectable>(1000)
    private val currentSelector = AtomicReference<Selector?>()

    override fun close() {
        closed = true

        withSelector(false) {
            interestQueue.put(Poison)
            wakeup()
        }
    }

    suspend override fun select(selectable: AsyncSelectable, interest: SelectInterest) {
        require(selectable !== Poison)

        var selector = currentSelector.get()
        if (selector == null) {
            selector = suspendCancellableCoroutine<Selector> { c ->
                c.disposeOnCancel(selectable)
                withSelector {
                    c.resume(this)
                }
            }
        }

        select(selector, selectable, interest, { interestQueue.put(it) })
        withSelector { wakeup() }
    }

    override fun notifyClosed(s: AsyncSelectable) {
        withSelector(false) {
            s.channel.keyFor(this)?.subject = null
            wakeup()
        }
    }

    private fun withSelector(forceStart: Boolean = true, block: Selector.() -> Unit) {
        val existing = currentSelector.get()
        if (closed && forceStart) {
            throw IllegalStateException("SelectorManager is closed")
        } else if (existing != null) {
            block(existing)
        } else if (forceStart) {
            start(block)
        }
    }

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
                } catch (e: ClosedSelectorException) {
                    closed = true
                    break
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
            val preselected = selector.selectNow()
            val hasInterestToProcess = interestQueue.isNotEmpty()

            if (preselected == 0 && selector.keys().isEmpty() && !hasInterestToProcess && !closed) {
                val e = interestQueue.poll(idleTime, idleTimeUnit) ?: return
                if (e === Poison) break

                applyInterest(selector, e)
            } else if (preselected > 0 || hasInterestToProcess || selector.select() > 0) {
                selector.selectedKeys().forEach {
                    tryHandleSelectedKey(selector, it)
                }
                selector.selectedKeys().clear()
            }

            processInterestQueue(selector)
        }
    }

    private fun processInterestQueue(selector: Selector) {
        while (!closed) {
            val e = interestQueue.poll() ?: break
            if (e == Poison) break

            applyInterest(selector, e)
        }
    }

    private fun start(block: (Selector.() -> Unit)?) {
        if (currentSelector.get() == null) {
            selectorsCoroutineDispatcher.dispatch(selectorsCoroutineDispatcher, Runnable { selectorMain(block) })
        }
    }

    companion object {
        private val Poison = object : AsyncSelectable {
            override val suspensions: InterestSuspensionsMap
                get() = throw UnsupportedOperationException()

            override val channel: SelectableChannel
                get() = throw UnsupportedOperationException()

            override val interestedOps: Int
                get() = 0

            override fun close() {
            }

            override fun dispose() {
            }

            override fun interestOp(interest: SelectInterest, state: Boolean) {
            }
        }
    }
}
