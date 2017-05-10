package kotlinx.http.server.ktor

import kotlinx.sockets.*
import org.jetbrains.ktor.cio.*
import java.nio.*

class DirectResponseWriteChannel(val limit: Long, val socket: ReadWriteSocket) : WriteChannel {
    private var written = 0L

    override fun close() {
    }

    suspend override fun flush() {
    }

    suspend override fun write(src: ByteBuffer) {
        val size = src.remaining()

        if (written + size > limit) {
            throw IllegalArgumentException("Response body size limit violation")
        }

        socket.write(src)
        written += (size - src.remaining())
    }
}