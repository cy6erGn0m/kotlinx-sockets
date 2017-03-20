package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.nio.channels.*

interface AsyncSelectable {
    val channel: SelectableChannel
    val interestedOps: Int
    suspend fun onSelected(key: SelectionKey)
}

internal fun AsyncSelectable.interestFlag(selector: SelectorManager, flag: Int, state: Boolean, setter: (Int) -> Unit) {
    val newOps = if (state) interestedOps or flag else interestedOps and flag.inv()
    if (interestedOps != newOps) {
        launch(selector.dispatcher) {
            setter(newOps)
            selector.registerSafe(this@interestFlag)
        }
    }
}

internal fun AsyncSelectable.interestPresent(flag: Int): Boolean = interestedOps and flag != 0