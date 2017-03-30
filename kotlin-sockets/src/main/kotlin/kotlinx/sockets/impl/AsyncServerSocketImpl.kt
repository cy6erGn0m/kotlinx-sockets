package kotlinx.sockets.impl

import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*

internal class AsyncServerSocketImpl(override val channel: ServerSocketChannel, val selector: SelectorManager) : AsyncServerSocket, SelectableBase() {
    init {
        require(!channel.isBlocking)
    }

    override val localAddress: SocketAddress
        get() = channel.localAddress

    suspend override fun accept(): AsyncSocket {
        while (true) {
            channel.accept()?.let { return accepted(it) }

            interestOp(SelectInterest.ACCEPT, true)
            selector.select(this, SelectInterest.ACCEPT)
        }
    }

    private fun accepted(nioChannel: SocketChannel): AsyncSocket {
        interestOp(SelectInterest.ACCEPT, false)
        nioChannel.configureBlocking(false)
        return AsyncSocketImpl(nioChannel, selector)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.notifyClosed(this)
        }
    }
}