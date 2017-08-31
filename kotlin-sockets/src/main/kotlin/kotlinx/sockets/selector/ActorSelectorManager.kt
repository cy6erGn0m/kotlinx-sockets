package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.atomic.*

class ActorSelectorManager : SelectorManagerSupport(), Closeable {
    private val dispatcher = ioCoroutineDispatcher

    @Volatile
    private var selectorRef: Selector? = null

    private val wakeup = AtomicLong()

    @Volatile
    private var inSelect = false

    private val mb = actor<Selectable>(dispatcher, capacity = kotlinx.coroutines.experimental.channels.Channel.UNLIMITED) {
        provider.openSelector()!!.use { selector ->
            selectorRef = selector
            try {
                process(channel, selector)
            } finally {
                channel.close()
                cancelAllSuspensions(selector)
                selectorRef = null
            }

            channel.consumeEach {
                cancelAllSuspensions(it, ClosedSendChannelException("Failed to apply interest: selector closed"))
            }
        }
    }

    private suspend fun process(mb: ReceiveChannel<Selectable>, selector: Selector) {
        while (true) {
            processInterests(mb, selector)

            if (pending > 0) {
                if (select(selector) > 0) {
                    handleSelectedKeys(selector.selectedKeys(), selector.keys())
                }
            } else if (cancelled > 0) {
                selector.selectNow()
                if (pending > 0) {
                    handleSelectedKeys(selector.selectedKeys(), selector.keys())
                }
            } else {
                val received = mb.receiveOrNull() ?: break
                applyInterest(selector, received)
            }
        }
    }

    private fun select(selector: Selector): Int {
        inSelect = true
        return if (wakeup.get() == 0L) {
            val count = selector.select()
            inSelect = false
            count
        } else {
            inSelect = false
            wakeup.set(0)
            selector.selectNow()
        }
    }

    private fun selectWakeup() {
        if (wakeup.incrementAndGet() == 1L && inSelect) {
            selectorRef?.wakeup()
        }
    }

    private fun processInterests(mb: ReceiveChannel<Selectable>, selector: Selector) {
        while (true) {
            val selectable = mb.poll() ?: break
            applyInterest(selector, selectable)
        }
    }

    override fun notifyClosed(s: Selectable) {
        cancelAllSuspensions(s, ClosedChannelException())
        selectorRef?.let { selector ->
            s.channel.keyFor(selector)?.let { k ->
                k.cancel()
                selectWakeup()
            }
        }
    }

    override fun publishInterest(selectable: Selectable) {
        try {
            mb.offer(selectable)
            selectWakeup()
        } catch (t: Throwable) {
            cancelAllSuspensions(selectable, t)
        }
    }

    override fun close() {
        mb.close()
        selectWakeup()
    }
}