package kotlinx.sockets.selector

import kotlinx.sockets.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

/**
 * A selectable entity with selectable NIO [channel], [interestedOps] subscriptions
 */
interface AsyncSelectable {
    /**
     * associated channel
     */
    val channel: SelectableChannel

    /**
     * current interests
     */
    val interestedOps: Int

    /**
     * called by the selector when [key] is selected.
     * It is always called from selector loop so it should work fast and should never block.
     */
    fun onSelected(key: SelectionKey)

    /**
     * called by the selector when selection failed or [onSelected] handled failed.
     * It is always called from selector loop so it should work fast and should never block.
     */
    fun onSelectionFailed(t: Throwable)
}

internal abstract class SelectableBase : AsyncSelectable {
    @Volatile
    override var interestedOps: Int = 0
        internal set
}

internal fun SelectableBase.interestOp(flag: Int, state: Boolean) {
    interestedOps = if (state) interestedOps or flag else interestedOps and flag.inv()
}

internal fun <T> SelectableBase.onSelectedGeneric(key: SelectionKey, op: Int, continuation: AtomicReference<Continuation<T>?>, block: (Continuation<T>) -> Unit): Boolean {
    if (!key.readyOp(op)) {
        if (continuation.get() != null) {
            interestOp(op, true)
            return true
        }
    } else {
        if (!continuation.invokeIfPresent(block)) {
            interestedOps = 0
            return true
        }
    }

    return false
}
