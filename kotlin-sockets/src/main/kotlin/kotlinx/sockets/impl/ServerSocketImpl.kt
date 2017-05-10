package kotlinx.sockets.impl

import kotlinx.sockets.ServerSocket
import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*

internal class ServerSocketImpl(override val channel: ServerSocketChannel, val selector: SelectorManager) : ServerSocket, SelectableBase() {
    init {
        require(!channel.isBlocking)
    }

    override val localAddress: SocketAddress
        get() = channel.localAddress

    suspend override fun accept(): Socket {
        while (true) {
            channel.accept()?.let { return accepted(it) }

            interestOp(SelectInterest.ACCEPT, true)
            selector.select(this, SelectInterest.ACCEPT)
        }
    }

    private fun accepted(nioChannel: SocketChannel): Socket {
        interestOp(SelectInterest.ACCEPT, false)
        nioChannel.configureBlocking(false)
        nioChannel.setOption(StandardSocketOptions.TCP_NODELAY, true)
        return SocketImpl(nioChannel, selector)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.notifyClosed(this)
        }
    }
}