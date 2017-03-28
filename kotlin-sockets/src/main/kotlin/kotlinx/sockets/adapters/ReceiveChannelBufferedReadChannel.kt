package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class ReceiveChannelBufferedReadChannel(val source: ReceiveChannel<ByteBuffer>, pool: Channel<ByteBuffer>, order: ByteOrder) : BufferedReadChannel(pool, order) {

    override suspend fun receiveImpl(): ByteBuffer? {
        return source.receiveOrNull()
    }

    override val isSourceClosed: Boolean
        get() = source.isClosedForReceive
}

fun ReceiveChannel<ByteBuffer>.buffered(pool: Channel<ByteBuffer>, order: ByteOrder): BufferedReadChannel = ReceiveChannelBufferedReadChannel(this, pool, order)

