package kotlinx.http.server.ktor

import kotlinx.sockets.*
import org.jetbrains.ktor.cio.*
import java.nio.*

class LimitedRequestReadChannel(val buffer: ByteBuffer, val source: ReadWriteSocket, var remaining: Long = 0L) : ReadChannel {

    override fun close() {
        if (remaining > 0L) {
            source.close() // TODO ???
        }
    }

    suspend override fun read(dst: ByteBuffer): Int {
        if (remaining == 0L) return -1

        while (buffer.hasRemaining() && dst.hasRemaining() && remaining > 0L) {
            dst.put(buffer.get())
            remaining--
        }

        if (remaining >= dst.remaining()) {
            val rc = source.read(dst)
            if (rc > 0) remaining -= rc
            return rc
        }

        val sub = dst.slice()
        sub.limit(minOf(dst.remaining(), remaining.toInt()))
        val rc = source.read(sub)
        if (rc > 0) {
            remaining -= rc
            dst.position(dst.position() + rc)
        }

        return rc
    }
}