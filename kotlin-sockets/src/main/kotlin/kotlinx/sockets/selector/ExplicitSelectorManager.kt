package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.*

/**
 * A selector manager that creates selector and loop thread lazily and only disposes it on [close].
 */
class ExplicitSelectorManager : Closeable, DisposableHandle, SelectorManagerSupport() {
    @Volatile
    private var closed = false
    private val selector = lazy { ensureStarted(); Selector.open()!! }
    private val interestQueueLF = LockFreeLinkedListHead()

    private val selectorJob = launch(selectorsCoroutineDispatcher, CoroutineStart.LAZY) {
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

    override fun notifyClosed(s: Selectable) {
        if (selector.isInitialized()) {
            s.channel.keyFor(selector.value)?.let { key ->
                key.subject?.let { attachment ->
                    notifyClosedImpl(selector.value, key, attachment)
                }

                key.subject = null
                selector.value.wakeup()
            }
        }
    }

    override fun publishInterest(selectable: Selectable) {
        interestQueueLF.addLast(selectable.node)
        selector.value.wakeup()
    }

    private tailrec fun selectorLoop(selector: Selector) {
        if (!closed && selector.select() > 0) {
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()

                handleSelectedKey(key)
            }
        }

        if (closed) return

        while (!closed) {
            val node = interestQueueLF.removeFirstOrNull() ?: break
            val selectable = (node as SelectableNode).selectable
            applyInterest(selector, selectable)
        }

        selectorLoop(selector)
    }

    private fun ensureStarted() {
        if (closed) throw ClosedSelectorException()
        selectorJob.start()
    }
}
