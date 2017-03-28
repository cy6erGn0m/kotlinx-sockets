package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class ReceiveChannelBufferedReadChannel(val source: kotlinx.coroutines.experimental.channels.ReceiveChannel<ByteBuffer>, pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder) : kotlinx.sockets.channels.BufferedReadChannel(pool, order) {

    override suspend fun receiveImpl(): java.nio.ByteBuffer? {
        return source.receiveOrNull()
    }

    override val isSourceClosed: Boolean
        get() = source.isClosedForReceive
}

fun kotlinx.coroutines.experimental.channels.ReceiveChannel<ByteBuffer>.buffered(pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder): kotlinx.sockets.channels.BufferedReadChannel = kotlinx.sockets.adapters.ReceiveChannelBufferedReadChannel(this, pool, order)

