package kotlinx.sockets.channels

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

abstract class BufferedWriteChannel internal constructor(val pool: Channel<ByteBuffer>, order: ByteOrder) : WriteChannel {

    private var buffer: ByteBuffer = Empty
    var order: ByteOrder = order
        set(newValue) {
            field = newValue
            buffer.order(newValue)
        }

    fun putByte(value: Byte) {
        buffer.put(value)
    }

    fun putUByte(value: Int) {
        require(value <= 0xff)
        buffer.put(value.toByte())
    }

    fun putShort(value: Short) {
        buffer.putShort(value)
    }

    fun putUShort(value: Int) {
        require(value >= 0) { "value shouldn't be negative"}
        require(value <= 0xffff) { "value is too big $value" }

        buffer.putShort(value.toShort())
    }

    fun putInt(value: Int) {
        buffer.putInt(value)
    }

    fun putUInt(value: Long) {
        buffer.putInt(value.toInt())
    }

    fun putLong(value: Long) {
        buffer.putLong(value)
    }

    fun putFloat(value: Float) {
        buffer.putFloat(value)
    }

    fun putDouble(value: Double) {
        buffer.putDouble(value)
    }

    suspend fun putString(s: String, charset: Charset) {
        putString(s, charset.newEncoder())
    }

    suspend fun putString(s: String, encoder: CharsetEncoder) {
        val from = CharBuffer.wrap(s)
        encoder.reset()

        while (from.hasRemaining()) {
            if (buffer.hasRemaining()) {
                encoder.encode(from, buffer, true)
            }

            if (!buffer.hasRemaining()) {
                flush()
            }
        }
    }

    suspend override fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            while (src.hasRemaining() && buffer.hasRemaining()) {
                buffer.put(src)
            }

            if (!buffer.hasRemaining()) {
                flush()
            }
        }
    }

    override fun shutdownOutput() {
        runBlocking {
            flush()
            if (pool !== Empty) {
                pool.offer(buffer)
                buffer = Empty
            }

            shutdownImpl()
        }
    }

    suspend fun ensureCapacity(required: Int) {
        if (buffer.remaining() < required) {
            flush()
        }
        if (buffer.remaining() < required) {
            throw IllegalArgumentException("required capacity couldn't be satisfied: $required")
        }
    }

    suspend fun flush() {
        buffer.flip()
        if (buffer.hasRemaining()) {
            doWrite(buffer)
            newBuffer()
        } else if (buffer === Empty) {
            newBuffer()
        } else {
            buffer.clear()
        }
    }

    protected abstract suspend fun doWrite(buffer: ByteBuffer)
    protected abstract fun shutdownImpl()

    private suspend fun newBuffer() {
        buffer = pool.receive().apply {
            clear()
            order(order)
        }
    }

    companion object {
        private val Empty = ByteBuffer.allocate(0)!!
    }
}
