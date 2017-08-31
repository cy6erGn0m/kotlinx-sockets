package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*

internal class DatagramSocketImpl(override val channel: DatagramChannel, selector: SelectorManager)
    : BoundDatagramSocket, ConnectedDatagramSocket,
        Selectable by SelectableBase(channel),
        NIOSocketImpl<DatagramChannel>(channel, selector, DefaultDatagramByteBufferPool) {

    override val localAddress: SocketAddress
        get() = channel.localAddress ?: throw IllegalStateException("Channel is not yet bound")

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress ?: throw IllegalStateException("Channel is not yet connected")

    override fun shutdownOutput() {
    }

    suspend override fun receive(): Datagram {
        val buffer = ByteBuffer.allocateDirect(65536)
        val address = channel.receive(buffer) ?: return receiveSuspend(buffer)

        interestOp(SelectInterest.READ, false)
        buffer.flip()
        return Datagram(buildPacket { writeFully(buffer) }, address)
    }

    private tailrec suspend fun receiveSuspend(buffer: ByteBuffer): Datagram {
        interestOp(SelectInterest.READ, true)
        selector.select(this, SelectInterest.READ)

        val address = channel.receive(buffer) ?: return receiveSuspend(buffer)

        interestOp(SelectInterest.READ, false)
        buffer.flip()
        return Datagram(buildPacket { writeFully(buffer) }, address)
    }

    suspend override fun receive(dst: ByteBuffer): SocketAddress {
        val datagram = receive()
        datagram.packet.readLazy(dst)
        return datagram.address
    }

    suspend override fun send(datagram: Datagram) {
        val buffer = ByteBuffer.allocateDirect(datagram.packet.remaining)
        datagram.packet.readLazy(buffer)
        buffer.flip()

        val rc = channel.send(buffer, datagram.address)
        if (rc == 0) {
            sendSuspend(buffer, datagram.address)
        } else {
            interestOp(SelectInterest.WRITE, false)
        }
    }

    private tailrec suspend fun sendSuspend(buffer: ByteBuffer, address: SocketAddress) {
        interestOp(SelectInterest.WRITE, true)
        selector.select(this, SelectInterest.WRITE)

        if (channel.send(buffer, address) == 0) sendSuspend(buffer, address)
        else interestOp(SelectInterest.WRITE, false)
    }

    suspend override fun write(src: ByteBuffer, target: SocketAddress) {
        send(Datagram(buildPacket { writeFully(src) }, target))
    }

    override fun close() {
        super<NIOSocketImpl>.close()
    }

    override fun dispose() {
        super<NIOSocketImpl>.dispose()
    }
}