package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*

internal class SocketImpl<out S : SocketChannel>(override val channel: S, val selector: SelectorManager) : SelectableBase(), Socket {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress

    internal suspend fun connect(target: SocketAddress): Socket {
        if (channel.connect(target)) return this

        wantConnect(true)
        selector.select(this, SelectInterest.CONNECT)

        while (true) {
            if (channel.finishConnect()) break

            wantConnect(true)
            selector.select(this, SelectInterest.CONNECT)
        }

        wantConnect(false)

        return this
    }

    override fun attachForReading(channel: ByteChannel) {
        attachForReadingImpl(channel, this.channel, this, selector)
    }

    override fun attachForWriting(channel: ByteChannel) {
        attachForWritingImpl(channel, this.channel, this, selector)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.notifyClosed(this)
        }
    }

    private fun wantConnect(state: Boolean = true) {
        interestOp(SelectInterest.CONNECT, state)
    }
}
