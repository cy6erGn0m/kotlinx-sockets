package kotlinx.sockets.channels.impl

import kotlinx.sockets.channels.*
import java.nio.*
import java.nio.charset.*

internal class CharWriteChannelImpl(val sink: WriteChannel, val charset: Charset, val buffer: ByteBuffer) : CharWriteChannel {
    private val encoder by lazy { charset.newEncoder()!! }

    init {
        buffer.position(buffer.limit())
    }

    suspend override fun write(src: CharBuffer) {
        while (src.hasRemaining() || buffer.hasRemaining()) {
            buffer.compact()
            val rc = encoder.encode(src, buffer, false)
            buffer.flip()

            if (rc.isError) {
                buffer.compact()
                rc.throwException()
            }

            sink.write(buffer)
        }
    }

    override fun shutdownOutput() {
        sink.shutdownOutput()
    }
}

/**
 * Creates a character write channel
 */
fun WriteChannel.asCharWriteChannel(charset: Charset = Charsets.UTF_8,
                                    buffer: ByteBuffer = ByteBuffer.allocate(8192)): CharWriteChannel
        = CharWriteChannelImpl(this, charset, buffer)

suspend fun CharWriteChannel.write(s: String) {
    val cb = CharBuffer.wrap(s)
    while (cb.hasRemaining()) {
        write(cb)
    }
}
