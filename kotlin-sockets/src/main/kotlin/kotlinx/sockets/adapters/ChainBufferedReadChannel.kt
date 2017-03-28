package kotlinx.sockets.adapters

import java.nio.*

internal class ChainBufferedReadChannel(val source: kotlinx.sockets.ReadChannel, pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder) : kotlinx.sockets.channels.BufferedReadChannel(pool, order) {

    private var closed = false

    suspend override fun receiveImpl(): java.nio.ByteBuffer? {
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

fun kotlinx.sockets.ReadChannel.buffered(pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder): kotlinx.sockets.channels.BufferedReadChannel = kotlinx.sockets.adapters.ChainBufferedReadChannel(this, pool, order)


