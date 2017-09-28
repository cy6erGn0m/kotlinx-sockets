package kotlinx.sockets.impl

import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*

internal class SocketImpl<out S : SocketChannel>(override val channel: S, selector: SelectorManager) : Selectable by SelectableBase(channel), NIOSocketImpl<S>(channel, selector, pool = null), Socket {
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

    override fun dispose() {
        super<NIOSocketImpl>.dispose()
    }

    override fun close() {
        super<NIOSocketImpl>.close()
    }

    private fun wantConnect(state: Boolean = true) {
        interestOp(SelectInterest.CONNECT, state)
    }
}
