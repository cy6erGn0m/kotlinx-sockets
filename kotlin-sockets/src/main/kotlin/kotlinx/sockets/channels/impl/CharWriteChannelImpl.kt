package kotlinx.sockets.channels.impl

import kotlinx.sockets.channels.*
import java.nio.*
import java.nio.charset.*

@Deprecated("", level = DeprecationLevel.ERROR)
fun WriteChannel.asCharWriteChannel(charset: Charset = Charsets.UTF_8,
                                    buffer: ByteBuffer = ByteBuffer.allocate(8192)): CharWriteChannel
        = TODO()

suspend fun CharWriteChannel.write(s: String) {
    val cb = CharBuffer.wrap(s)
    while (cb.hasRemaining()) {
        write(cb)
    }
}
