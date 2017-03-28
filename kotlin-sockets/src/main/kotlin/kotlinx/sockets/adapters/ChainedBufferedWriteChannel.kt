package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class ChainedBufferedWriteChannel(val out: WriteChannel, pool: Channel<ByteBuffer>, order: ByteOrder) : BufferedWriteChannel(pool, order) {

    suspend override fun doWrite(buffer: ByteBuffer) {
        try {
            out.write(buffer)
        } finally {
            pool.offer(buffer)
        }
    }

    override fun closeImpl() {
        out.close()
    }
}

fun WriteChannel.buffered(pool: Channel<ByteBuffer>, order: ByteOrder = ByteOrder.nativeOrder()): BufferedWriteChannel {
    return ChainedBufferedWriteChannel(this, pool, order)
}
