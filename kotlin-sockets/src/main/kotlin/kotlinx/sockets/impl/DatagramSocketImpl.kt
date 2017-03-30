package kotlinx.sockets.impl

import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.nio.channels.*

internal class DatagramSocketImpl(override val channel: DatagramChannel, val selector: SelectorManager) : AsyncBoundDatagramSocket, AsyncConnectedDatagramSocket, SelectableBase() {

    override val localAddress: SocketAddress
        get() = channel.localAddress ?: throw IllegalStateException("Channel is not yet bound")

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress ?: throw IllegalStateException("Channel is not yet connected")

    override fun shutdownOutput() {
    }

    suspend override fun receive(dst: ByteBuffer): SocketAddress {
        while (true) {
            channel.receive(dst)?.let { interestOp(SelectInterest.READ, false); return it }

            interestOp(SelectInterest.READ, true)
            selector.select(this, SelectInterest.READ)
        }
    }

    suspend override fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = channel.read(dst)

            if (rc > 0) {
                interestOp(SelectInterest.READ, false)
                return rc
            }

            interestOp(SelectInterest.READ, true)
            selector.select(this, SelectInterest.READ)
        }
    }

    suspend override fun write(src: ByteBuffer, target: SocketAddress) {
        while (true) {
            val rc = channel.send(src, target)
            if (rc > 0) {
                interestOp(SelectInterest.WRITE, false)
                return
            }

            interestOp(SelectInterest.WRITE, true)
            selector.select(this, SelectInterest.WRITE)
        }
    }

    suspend override fun write(src: ByteBuffer) {
        while (true) {
            val rc = channel.write(src)
            if (rc > 0) {
                interestOp(SelectInterest.WRITE, false)
                return
            }

            interestOp(SelectInterest.WRITE, true)
            selector.select(this, SelectInterest.WRITE)
        }
    }

    override fun close() {
        channel.close()
    }
}