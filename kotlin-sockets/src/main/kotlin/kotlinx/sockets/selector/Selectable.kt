package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.atomic.*

abstract class SelectableNode : LockFreeLinkedListNode() {
    abstract val selectable: Selectable
}

/**
 * A selectable entity with selectable NIO [channel], [interestedOps] subscriptions
 */
interface Selectable : Closeable, DisposableHandle {

    val node: SelectableNode

    val suspensions: InterestSuspensionsMap

    /**
     * associated channel
     */
    val channel: SelectableChannel

    /**
     * current interests
     */
    val interestedOps: Int

    /**
     * Apply [state] flag of [interest] to [interestedOps]. Notice that is doesn't actually change selection key.
     */
    fun interestOp(interest: SelectInterest, state: Boolean)
}

internal class SelectableBase(override val channel: SelectableChannel) : Selectable, SelectableNode() {
    override val selectable: Selectable get() = this
    override val node get() = this

    private val interestedOpsAtomic = AtomicInteger(0)

    override val suspensions = InterestSuspensionsMap()

    override val interestedOps: Int
        get() = interestedOpsAtomic.get()

    override fun interestOp(interest: SelectInterest, state: Boolean) {
        val flag = interest.flag

        while (true) {
            val before = interestedOpsAtomic.get()
            val after = if (state) before or flag else before and flag.inv()
            if (interestedOpsAtomic.compareAndSet(before, after)) break
        }
    }

    // TODO!!!
    override fun close() {
    }

    override fun dispose() {
    }
}

