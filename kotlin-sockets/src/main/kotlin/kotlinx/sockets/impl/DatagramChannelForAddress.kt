package kotlinx.sockets.impl

import kotlinx.sockets.channels.*
import java.net.*
import java.nio.*

internal class DatagramChannelForAddress(val out: DatagramWriteChannel, val address: SocketAddress) : WriteChannel {
    override fun shutdownOutput() {
        out.shutdownOutput()
    }

    suspend override fun write(src: ByteBuffer) {
        out.write(src, address)
    }
}

fun DatagramWriteChannel.channelFor(target: SocketAddress): WriteChannel {
    return DatagramChannelForAddress(this, target)
}
