package kotlinx.http.server.ktor

import kotlinx.http.server.*
import kotlinx.sockets.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.cio.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import java.nio.*
import java.util.concurrent.*

class CApplicationCall(application: Application,
                       _request: HttpRequest,
                       private val socket: ReadWriteSocket,
                       buffer: ByteBuffer,
                       private val pool: BlockingQueue<ByteBuffer>
                       ) : BaseApplicationCall(application) {
    override val request = CApplicationRequest(this, _request, socket, buffer)
    override val response = CApplicationResponse(this)

    suspend override fun respondUpgrade(upgrade: FinalContent.ProtocolUpgrade) {
        TODO("not implemented")
    }

    private var responseChannel: WriteChannel? = null

    suspend override fun respondFinalContent(content: FinalContent) {
        commitHeaders(content)

        val buffer = pool.poll() ?: ByteBuffer.allocateDirect(8192)
        try {
            response.render(buffer, socket)

            val responseContentLength = response.headers[HttpHeaders.ContentLength]?.toLong()
            responseChannel = when {
                responseContentLength != null -> DirectResponseWriteChannel(responseContentLength, socket)
                else -> ChunkedResponseWriteChannel(buffer, socket)
            }

            // TODO need to be reused instead
            return when (content) {
                is FinalContent.ProtocolUpgrade -> respondUpgrade(content)

            // ByteArrayContent is most efficient
                is FinalContent.ByteArrayContent -> respondFromBytes(content.bytes())

            // WriteChannelContent is more efficient than ReadChannelContent
                is FinalContent.WriteChannelContent -> content.writeTo(responseChannel())

            // Pipe is least efficient
                is FinalContent.ReadChannelContent -> respondFromChannel(content.readFrom())

            // Do nothing, but maintain `when` exhaustiveness
                is FinalContent.NoContent -> { /* no-op */
                }
            }
        } finally {
            pool.offer(buffer)
        }
    }

    override fun responseChannel(): WriteChannel {
        return responseChannel ?: throw IllegalStateException("Should be only called from respondFinalContent")
    }
}