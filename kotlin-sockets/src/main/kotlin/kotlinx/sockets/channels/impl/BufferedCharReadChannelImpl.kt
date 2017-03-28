package kotlinx.sockets.channels.impl

import kotlinx.sockets.channels.*
import java.nio.*

internal class BufferedCharReadChannelImpl(val source: CharReadChannel, private var buffer: CharBuffer) : BufferedCharReadChannel {
    private val sb by lazy(::StringBuilder)

    suspend override fun read(dst: CharBuffer): Int {
        if (fill()) return -1

        val rem = minOf(buffer.remaining(), dst.remaining()) / 2
        for (i in 1..rem) {
            dst.put(buffer.get())
        }

        return rem
    }

    suspend override fun read(): Int {
        if (fill()) return -1

        return buffer.get().toInt()
    }

    override suspend fun readLine(): String? {
        if (fill()) {
            return null
        }

        sb.delete(0, sb.length)
        return source.readLineTo(sb, buffer).first.toString()
    }

    private suspend fun fill() = source.fill(buffer)

    companion object {
        private val EmptyBuffer = CharBuffer.allocate(0)
    }
}

/**
 * Creates buffered char read channel with buffer of [size] chars.
 * [bufferSupplier] is useful
 */
fun CharReadChannel.buffered(size: Int = 8192, bufferSupplier: (Int) -> CharBuffer = CharBuffer::allocate): BufferedCharReadChannel = BufferedCharReadChannelImpl(this, bufferSupplier(size).apply { position(limit()) })
