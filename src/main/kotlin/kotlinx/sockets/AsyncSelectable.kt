package kotlinx.sockets

import java.nio.channels.*

interface AsyncSelectable {
    val channel: SelectableChannel
    val interestedOps: Int
    suspend fun onSelected(key: SelectionKey)
}