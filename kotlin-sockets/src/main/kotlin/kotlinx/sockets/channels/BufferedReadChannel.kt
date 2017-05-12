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

    /**
     * Provides fold operation for every nio ByteBuffer until end or if stop function were called.
     * All unprocessed bytes in the buffer will be discarded before next iteration except the case when
     * fold has been stopped via stop function.
     */
    suspend fun <A : Any> fold(initial: A, scan: (A, ByteBuffer, stop: () -> Unit) -> A): A {
        fill(0)

        var state = initial
        var stopped = false
        fun stop() {
            stopped = true
        }
        val stopRef = ::stop

        while (!stopped && buffer.hasRemaining()) {
            state = scan(state, buffer, stopRef)
            if (stopped) break

            buffer.position(buffer.limit())
            fill(0)
        }

        return state
    }

    /**
     * Reads a line from channel, only ISO-8859-1 is supported
     * Supported line endings are: CR, LF, CR+LF
     */
    suspend fun <A : Appendable> readASCIILineTo(out: A): Boolean {
        var cr = false

        return fold(false) { rc, bb, stop ->
            loop@while (bb.hasRemaining()) {
                val ch = bb.get().toChar()
                when {
                    ch == '\r' -> cr = true
                    ch == '\n' -> {
                        stop()
                        break@loop
                    }
                    cr -> {
                        bb.position(bb.position() - 1)
                        break@loop
                    }
                    else -> out.append(ch)
                }
            }

            true
        }
    }

    suspend fun readASCIILine(estimate: Int = 16): String? {
        val sb = StringBuilder(estimate)
        return if (readASCIILineTo(sb)) sb.toString() else null
    }

    suspend fun fill(required: Int) {
        while (buffer.remaining() < required || !buffer.hasRemaining()) {
            val next = remainingBuffer ?: receiveImpl() ?: break
            val old = buffer

            remainingBuffer = null

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
                if (next.hasRemaining()) remainingBuffer = next
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
