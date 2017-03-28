package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class ChainBufferedReadChannel(val source: ReadChannel, pool: Channel<ByteBuffer>, order: ByteOrder) : BufferedReadChannel(pool, order) {

    private var closed = false

    suspend override fun receiveImpl(): ByteBuffer? {
        val next = pool.receive().apply {
            clear()
            order(order)
        }

        val rc = source.read(next)
        if (rc == -1) {
            closed = true
            pool.offer(next)
            return null
        }

        next.flip()
        return next
    }

    override val isSourceClosed: Boolean
        get() = closed
}

fun ReadChannel.bufferedRead(pool: Channel<ByteBuffer>, order: ByteOrder = ByteOrder.nativeOrder()): BufferedReadChannel = ChainBufferedReadChannel(this, pool, order)


