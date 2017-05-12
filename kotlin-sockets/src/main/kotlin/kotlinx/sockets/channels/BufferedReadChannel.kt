package kotlinx.sockets.channels

import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

abstract class BufferedReadChannel internal constructor(val pool: Channel<ByteBuffer>, order: ByteOrder) : ReadChannel {
    private var remaining: ByteBuffer? = null
    protected var buffer = Empty

    var order: ByteOrder = order
        set(newOrder) {
            field = newOrder
            buffer.order(newOrder)
        }

    suspend override fun read(dst: ByteBuffer): Int {
        if (!dst.hasRemaining()) {
            return if (isSourceClosed && !buffer.hasRemaining()) -1 else 0
        }

        if (!buffer.hasRemaining()) {
            if (isSourceClosed) return -1

            fill(1)
        }

        val before = dst.position()
        while (dst.hasRemaining() && buffer.hasRemaining()) {
            dst.put(buffer.get())
        }

        return dst.position() - before
    }

    suspend fun skipExact(qty: Int) {
        require(qty >= 0)

        var remaining = qty
        while (remaining > 0) {
            fill(1)

            val step = minOf(remaining, buffer.remaining())
            remaining -= step
            buffer.position(buffer.position() + step)
        }
    }

    fun getByte(): Byte = buffer.get()
    fun getUByte(): Int = buffer.get().toInt() and 0xff
    fun getShort(): Short = buffer.getShort()
    fun getUShort(): Int = buffer.getShort().toInt() and 0xffff
    fun getInt(): Int = buffer.getInt()
    fun getUInt(): Long = buffer.getInt().toLong() and 0xffffffffL
    fun getLong(): Long = buffer.getLong()
    fun getDouble(): Double = buffer.getDouble()
    fun getFloat(): Float = buffer.getFloat()

    suspend fun getStringByRawLength(length: Int, decoder: CharsetDecoder): String {
        val sb = StringBuilder((length * decoder.maxCharsPerByte()).toInt())
        decoder.reset()
        val bb = pool.receive()
        val cb = bb.asCharBuffer()
        cb.clear()

        var remaining = length

        while (remaining > 0) {
            if (!buffer.hasRemaining()) {
                fill(1)
            }

            val before = buffer.position()
            when {
                buffer.remaining() <= remaining -> {
                    decoder.decode(buffer, cb, buffer.remaining() >= remaining)
                    remaining -= (buffer.position() - before)
                }
                else -> {
                    val sub = buffer.slice()
                    sub.limit(remaining)
                    decoder.decode(sub, cb, true)
                    buffer.position(buffer.position() + sub.position())
                    remaining -= sub.position()
                }
            }

            cb.flip()
            sb.append(cb)
        }

        pool.offer(bb)

        return sb.toString()
    }

    /**
     * Reads a line from channel, only ISO-8859-1 is supported
     * Supported line endings are: CR, LF, CR+LF
     */
    suspend fun <A : Appendable> readASCIILineTo(out: A): Boolean {
        var cr = false

        fill(0)
        if (!buffer.hasRemaining()) return false

        loop@while (true) {
            if (!buffer.hasRemaining()) {
                fill(0)
                if (!buffer.hasRemaining()) break
            }

            while (buffer.hasRemaining()) {
                val ch = buffer.get().toChar()
                when (ch) {
                    '\r' -> cr = true
                    '\n' -> break@loop
                    else -> {
                        if (cr) {
                            buffer.position(buffer.position() - 1) // push back
                            break@loop
                        }

                        out.append(ch)
                    }
                }
            }
        }

        return true
    }

    suspend fun readASCIILine(estimate: Int = 16): String? {
        val sb = StringBuilder(estimate)
        return if (readASCIILineTo(sb)) sb.toString() else null
    }

    suspend fun fill(required: Int) {
        while (buffer.remaining() < required || !buffer.hasRemaining()) {
            val next = remaining ?: receiveImpl() ?: break
            val old = buffer

            remaining = null

            if (buffer.hasRemaining()) {
                val newBuffer = pool.receive()
                newBuffer.clear()
                newBuffer.order(order)
                newBuffer.put(buffer)
                while (next.hasRemaining() && newBuffer.hasRemaining()) {
                    newBuffer.put(next.get())
                }

                newBuffer.flip()
                buffer = newBuffer
                if (next.hasRemaining()) remaining = next
            } else {
                buffer = next
            }

            pool.offer(old)
        }

        if (buffer.remaining() < required) throw BufferUnderflowException()
    }

    protected abstract suspend fun receiveImpl(): ByteBuffer?
    protected abstract val isSourceClosed: Boolean

    companion object {
        protected val Empty = ByteBuffer.allocate(0)!!
    }
}
