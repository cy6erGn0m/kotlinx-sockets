package kotlinx.sockets.channels

import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

abstract class BufferedReadChannel internal constructor(val pool: Channel<ByteBuffer>, order: ByteOrder) : ReadChannel {
    private var remainingBuffer: ByteBuffer? = null
    private var buffer = Empty

    var order: ByteOrder = order
        set(newOrder) {
            field = newOrder
            buffer.order(newOrder)
        }

    val remaining: Int
        get() = buffer.remaining()

    val available: Int
        get() = buffer.remaining() + (remainingBuffer?.remaining() ?: 0)

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

    sealed class LookAheadResult {
        object Continue : LookAheadResult()
        object NeedMore : LookAheadResult()
        class NeedAtLeast(val bytes: Int): LookAheadResult()
        object End : LookAheadResult()
    }

    suspend fun lookAhead(visitor: (buffer: ByteBuffer, last: Boolean) -> LookAheadResult) {
        var lastResult: LookAheadResult = LookAheadResult.Continue
        var last = false

        do {
            if (lastResult is LookAheadResult.NeedAtLeast) {
                fill(lastResult.bytes)
            } else if (!buffer.hasRemaining() || lastResult == LookAheadResult.NeedMore) {
                val before = buffer.remaining()

                do {
                    if (!fill()) {
                        last = true
                        break
                    }
                } while (buffer.remaining() == before)
            }

            lastResult = visitor(buffer, last)
        } while (lastResult != LookAheadResult.End)
    }

    /**
     * Reads a line from channel, only ISO-8859-1 is supported
     * Supported line endings are: CR, LF, CR+LF
     */
    suspend fun <A : Appendable> readASCIILineTo(out: A): Boolean {
        var cr = false
        var eol = false
        var appended = false

        lookAhead { bb, last ->
            loop@while (bb.hasRemaining()) {
                val ch = bb.get().toChar()
                when {
                    ch == '\r' -> cr = true
                    ch == '\n' -> {
                        eol = true
                        return@lookAhead LookAheadResult.End
                    }
                    cr -> {
                        bb.position(bb.position() - 1)
                        eol = true
                        return@lookAhead LookAheadResult.End
                    }
                    else -> {
                        appended = true
                        out.append(ch)
                    }
                }

            }

            if (last) LookAheadResult.End else LookAheadResult.Continue
        }

        return eol || appended
    }

    suspend fun readASCIILine(estimate: Int = 16): String? {
        val sb = StringBuilder(estimate)
        return if (readASCIILineTo(sb)) sb.toString() else null
    }

    suspend fun fill(required: Int) {
        while (buffer.remaining() < required || !buffer.hasRemaining()) {
            if (!fill()) break
        }

        if (buffer.remaining() < required) throw BufferUnderflowException()
    }

    tailrec suspend fun fill(): Boolean {
        val next = remainingBuffer ?: receiveImpl() ?: return false
        if (!next.hasRemaining()) {
            pool.offer(next)
            return fill()
        }

        val old = buffer

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
            remainingBuffer = if (next.hasRemaining()) next else null
        } else {
            buffer = next
            remainingBuffer = null
        }

        pool.offer(old)
        return true
    }

    protected abstract suspend fun receiveImpl(): ByteBuffer?
    protected abstract val isSourceClosed: Boolean

    companion object {
        protected val Empty = ByteBuffer.allocate(0)!!
    }
}
