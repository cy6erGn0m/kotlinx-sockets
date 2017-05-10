package kotlinx.http.server.ktor

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import org.jetbrains.ktor.cio.*
import java.nio.*

class ChunkedResponseWriteChannel(private val buffer: ByteBuffer, val socket: ReadWriteSocket) : WriteChannel {
    override fun close() {
        runBlocking {
            buffer.clear()
            buffer.put(0x30)
            buffer.put(0x0d)
            buffer.put(0x0a)
            buffer.flip()

            while (buffer.hasRemaining()) {
                socket.write(buffer)
            }
        }
    }

    suspend override fun flush() {
    }

    suspend override fun write(src: ByteBuffer) {
        if (src.hasRemaining()) {
            buffer.position(12)

            var idx = 9
            var rem = src.remaining()

            while (rem > 0) {
                val v = rem % 10
                rem /= 10

                buffer.put(idx, (v + 0x30).toByte())
                idx--
            }

            if (idx == 9) {
                buffer.put(idx, 0)
            } else idx ++

            buffer.put(10, 0x0d)
            buffer.put(11, 0x0a)

            if (src.remaining() <= buffer.capacity() - 10) {
                buffer.put(src).flip().position(idx)
                return socket.write(buffer)
            }

            buffer.flip().position(idx)

            while (buffer.hasRemaining()) {
                socket.write(buffer)
            }

            while (src.hasRemaining()) {
                socket.write(src)
            }
        }
    }
}