package kotlinx.http.server.ktor

import kotlinx.sockets.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.response.*
import org.jetbrains.ktor.util.*
import java.nio.*

class CApplicationResponse(call: CApplicationCall) : BaseApplicationResponse(call) {
    private val headersMap = ValuesMapBuilder(true)
    private var responseStatus: HttpStatusCode = HttpStatusCode.NotFound

    override val headers = object : ResponseHeaders() {
        override fun getHostHeaderNames(): List<String> = headersMap.names().toList()
        override fun getHostHeaderValues(name: String) = headersMap.getAll(name).orEmpty()

        override fun hostAppendHeader(name: String, value: String) {
            headersMap.append(name, value)
        }
    }

    override fun setStatus(statusCode: HttpStatusCode) {
        responseStatus = statusCode
    }

    internal suspend fun render(buffer: ByteBuffer, socket: ReadWriteSocket) {
        val code = responseStatus.value
        val description = statusDescriptionBytes[responseStatus.value] ?: responseStatus.description.toByteArray(Charsets.ISO_8859_1)

        buffer.clear()
        buffer.put(Http11)
        buffer.put(0x20)

        buffer.put(((code / 100) + 0x30).toByte())
        buffer.put((((code / 10) % 10) + 0x30).toByte())
        buffer.put(((code % 10) + 0x30).toByte())
        buffer.put(0x20)

        buffer.put(description)
        buffer.put(0x0d)
        buffer.put(0x0a)

        for ((name, values) in headersMap.entries()) {
            for (value in values) {
                val size = name.length + 2 + value.length + 2

                while (buffer.remaining() < size) {
                    buffer.flip()
                    socket.write(buffer)
                    buffer.compact()

                    // TODO support for huge response headers
                    if (buffer.position() == 0) break
                }

                buffer.append(name)
                buffer.put(0x3a) // :
                buffer.put(0x20)
                buffer.append(value)
                buffer.put(0x0d)
                buffer.put(0x0a)
            }
        }

        while (buffer.remaining() < 2) {
            buffer.flip()
            socket.write(buffer)
            buffer.compact()
        }

        buffer.put(0x0d)
        buffer.put(0x0a)

        buffer.flip()

        while (buffer.hasRemaining()) {
            socket.write(buffer)
        }

        buffer.clear()
    }

    private fun ByteBuffer.append(s: String) {
        for (idx in 0..s.length - 1) {
            put(s[idx].toByte())
        }
    }

    companion object {
        val Http11 = "HTTP/1.1".toByteArray(Charsets.ISO_8859_1)
        val statusDescriptionBytes = HttpStatusCode.allStatusCodes.associateBy({ it.value }, { it.description.toByteArray(Charsets.ISO_8859_1) })
    }
}
