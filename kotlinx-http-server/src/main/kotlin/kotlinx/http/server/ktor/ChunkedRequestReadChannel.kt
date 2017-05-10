package kotlinx.http.server.ktor

import kotlinx.sockets.*
import org.jetbrains.ktor.cio.*
import java.nio.*

class ChunkedRequestReadChannel(val socket: ReadWriteSocket, private val buffer: ByteBuffer) : ReadChannel {
    private var chunkSize: Long = -1

    override fun close() {
        if (chunkSize != 0L) {
            socket.close() // TODO ???
        }
    }

    suspend override fun read(dst: ByteBuffer): Int {
        if (ensureChunk()) return -1
        if (chunkSize == 0L) return -1
        var copied = 0

        while (buffer.hasRemaining() && dst.hasRemaining() && chunkSize > 0L) {
            dst.put(buffer.get())
            chunkSize--
            copied++
        }

        if (chunkSize == 0L) chunkSize = -1L
        if (!dst.hasRemaining() || chunkSize == -1L) return copied

        val rc = if (chunkSize >= dst.remaining()) {
            socket.read(dst)
        } else {
            val rc = dst.slice().let { sub ->
                sub.limit(minOf(dst.remaining(), chunkSize.toInt()))
                socket.read(sub)
            }
            if (rc > 0) dst.position(dst.position() + rc)

            rc
        }

        when (rc) {
            -1 -> {
                chunkSize = 0
                return copied
            }
            else -> {
                chunkSize -= rc
                if (chunkSize == 0L) chunkSize = -1L
                copied += rc
            }
        }

        return copied
    }

    private suspend fun ensureChunk(): Boolean {
        var size = 0L

        chunkLoop@ while (chunkSize == -1L) {
            if (!buffer.hasRemaining()) {
                buffer.clear()
                if (socket.read(buffer) == -1) {
                    chunkSize = 0
                    return true
                }
                buffer.flip()
            }

            while (buffer.hasRemaining()) {
                val ch = buffer.get()

                when {
                    ch == 0x0D.toByte() -> {
                    }
                    ch == 0x0A.toByte() -> {
                        chunkSize = size
                        break@chunkLoop
                    }
                    ch > 0x39 || ch < 0x30 -> throw IllegalStateException("Wrong chunk number")
                    else -> size = size * 10 + (ch - 0x30)
                }
            }
        }

        return false
    }
}
