package kotlinx.sockets.impl

import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
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

    override suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = channel.read(dst)

            if (rc == 0 && dst.hasRemaining()) {
                wantMoreBytesRead()
                selector.select(this, SelectInterest.READ)
            } else {
                wantMoreBytesRead(false)
                return rc
            }
        }
    }

    override suspend fun write(src: ByteBuffer) {
        while (true) {
            val rc = channel.write(src)

            if (rc == 0 && src.hasRemaining()) {
                wantMoreSpaceForWrite()
                selector.select(this, SelectInterest.WRITE)
            } else {
                wantMoreSpaceForWrite(false)
                return
            }
        }
    }

    override fun shutdownOutput() {
        channel.shutdownOutput()
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

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestOp(SelectInterest.READ, state)
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestOp(SelectInterest.WRITE, state)
    }
}
