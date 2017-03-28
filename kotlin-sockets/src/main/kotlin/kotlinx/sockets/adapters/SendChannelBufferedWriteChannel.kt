package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.channels.*
import java.nio.*

internal class SendChannelBufferedWriteChannel(val out: kotlinx.coroutines.experimental.channels.SendChannel<ByteBuffer>, pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder) : kotlinx.sockets.channels.BufferedWriteChannel(pool, order) {

    suspend override fun doWrite(buffer: java.nio.ByteBuffer) {
        out.send(buffer)
    }

    override fun closeImpl() {
        out.close()
    }
}

fun kotlinx.coroutines.experimental.channels.SendChannel<ByteBuffer>.buffered(pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder = java.nio.ByteOrder.nativeOrder()) : kotlinx.sockets.channels.BufferedWriteChannel {
    return kotlinx.sockets.adapters.SendChannelBufferedWriteChannel(this, pool, order)
}

