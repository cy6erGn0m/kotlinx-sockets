package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class SendChannelBufferedWriteChannel(val out: SendChannel<ByteBuffer>, pool: Channel<ByteBuffer>, order: ByteOrder) : BufferedWriteChannel(pool, order) {

    suspend override fun doWrite(buffer: ByteBuffer) {
        out.send(buffer)
    }

    override fun shutdownImpl() {
        out.close()
    }
}

fun SendChannel<ByteBuffer>.buffered(pool: Channel<ByteBuffer>, order: ByteOrder = ByteOrder.nativeOrder()) : BufferedWriteChannel {
    return SendChannelBufferedWriteChannel(this, pool, order)
}

