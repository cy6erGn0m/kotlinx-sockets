package kotlinx.sockets.channels

import java.nio.*

abstract class BufferedWriteChannel internal constructor(val pool: kotlinx.coroutines.experimental.channels.Channel<ByteBuffer>, order: java.nio.ByteOrder) : kotlinx.sockets.WriteChannel {

    private var buffer: java.nio.ByteBuffer = kotlinx.sockets.channels.BufferedWriteChannel.Companion.Empty
    var order: java.nio.ByteOrder = order
        set(newValue) {
            field = newValue
            buffer.order(newValue)
        }

    fun putByte(value: Byte) {
        buffer.put(value)
    }

    fun putByteInt(value: Int) {
        require(value < 0xff)
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

    suspend fun putString(s: String, charset: java.nio.charset.Charset) {
        putString(s, charset.newEncoder())
    }

    suspend fun putString(s: String, encoder: java.nio.charset.CharsetEncoder) {
        val from = java.nio.CharBuffer.wrap(s)
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

    suspend override fun write(src: java.nio.ByteBuffer) {
        while (src.hasRemaining()) {
            while (src.hasRemaining() && buffer.hasRemaining()) {
                buffer.put(src)
            }

            if (!buffer.hasRemaining()) {
                flush()
            }
        }
    }

    override fun close() {
        kotlinx.coroutines.experimental.runBlocking {
            flush()
            if (pool !== Empty) {
                pool.offer(buffer)
                buffer = Empty
            }

            closeImpl()
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
        } else if (buffer === kotlinx.sockets.channels.BufferedWriteChannel.Companion.Empty) {
            newBuffer()
        } else {
            buffer.clear()
        }
    }

    protected abstract suspend fun doWrite(buffer: java.nio.ByteBuffer)
    protected abstract fun closeImpl()

    private suspend fun newBuffer() {
        buffer = pool.receive().apply {
            clear()
            order(order)
        }
    }

    companion object {
        private val Empty = java.nio.ByteBuffer.allocate(0)!!
    }
}
