package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.internal.*
import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.*

/**
 * A selector manager that creates a selector instance and run selection loop on-demand only.
 * Will automatically dispose selector instance and stop loop on idle of time specified by [idleTime] [idleTimeUnit] (5 seconds by default).
 *
 * Once closed auto-start will not work anymore.
 */
class OnDemandSelectorManager(val idleTime: Long = 5, val idleTimeUnit: TimeUnit = TimeUnit.SECONDS) : Closeable, SelectorManagerSupport() {
    @Volatile
    private var closed = false
    private val interestQueueLF = LockFreeLinkedListHead()
    private val currentSelector = AtomicReference<Selector?>()

    @Volatile
    private var loopThread: Thread? = null

    override fun close() {
        closed = true

        withSelector(false) {
            interestQueueLF.addLast(Poison)
            wakeup()
        }
    }

    override fun publishInterest(selectable: Selectable) {
        interestQueueLF.addLast(selectable.node)
        loopThread?.let { t -> LockSupport.unpark(t) }
        withSelector { wakeup() }
    }

    override fun notifyClosed(s: Selectable) {
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
            selectorsCoroutineDispatcher.dispatch(selectorsCoroutineDispatcher, Runnable { selectorMain(AtomicReference(block)) })
        }
    }

    private fun selectorMain(block: AtomicReference<(Selector.() -> Unit)?>) {
        var evaluated = false
        require(Thread.currentThread().threadGroup === selectorsGroup)

        provider.openSelector().use { selector ->
            while (!closed && currentSelector.compareAndSet(null, selector)) {
                try {
                    if (!evaluated) {
                        evaluated = true
                        block.getAndSet(null)?.invoke(selector)
                    }

                    do {
                        loop(selector)
                    } while (!interestQueueLF.isEmpty && !closed)
                } catch (e: ClosedSelectorException) {
                    closed = true
                    break
                } finally {
                    currentSelector.set(null)
                    if (interestQueueLF.isEmpty) {
                        break
                    }
                }
            }
        }

        if (!closed && !evaluated) {
            block.get()?.let { b ->
                withSelector(true, b)
            }
        }
    }

    private fun loop(selector: Selector) {
        while (!closed) {
            val preselected = selector.selectNow()
            processInterestQueue(selector)

            if (preselected == 0 && selector.keys().isEmpty() && !closed) {
                val node = interestQueueLF.removeFirstOrNull()
                val e = (node as? SelectableNode)?.selectable

                if (e != null) {
                    applyInterest(selector, e)
                } else if (node !== Poison) {
                    LockSupport.parkNanos(idleTimeUnit.toNanos(idleTime))
                }
            } else if (preselected > 0 || selector.select() > 0) {
                selector.selectedKeys().apply {
                    if (isNotEmpty()) {
                        forEach { handleSelectedKey(it) }
                        clear()
                    }
                }
            }
        }
    }

    private fun processInterestQueue(selector: Selector) {
        while (true) {
            val node = interestQueueLF.removeFirstOrNull()
            val e = (node as? SelectableNode)?.selectable ?: break
            applyInterest(selector, e)
        }
    }

    companion object {
        private val Poison = LockFreeLinkedListNode()
    }
}
