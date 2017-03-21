package kotlinx.sockets

import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

interface AsyncSelectable {
    val channel: SelectableChannel
    val interestedOps: Int

    fun onSelected(key: SelectionKey)
    fun onSelectionFailed(t: Throwable)
}

internal abstract class SelectableBase : AsyncSelectable {
    override abstract var interestedOps: Int
        internal set
}

internal fun SelectableBase.interestOp(flag: Int, state: Boolean) {
    interestedOps = if (state) interestedOps or flag else interestedOps and flag.inv()
}

internal fun AsyncSelectable.pushInterest(selector: SelectorManager) {
    if (interestedOps != 0) {
        selector.registerSafe(this@pushInterest)
    }
}

internal fun AsyncSelectable.pushInterestDirect(key: SelectionKey) {
    if (interestedOps != 0) {
        key.interestOps(interestedOps)
    }
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
