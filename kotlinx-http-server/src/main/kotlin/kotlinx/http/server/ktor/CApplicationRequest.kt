package kotlinx.http.server.ktor

import kotlinx.http.server.*
import kotlinx.sockets.*
import org.jetbrains.ktor.cio.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.request.*
import org.jetbrains.ktor.util.*
import java.nio.*

class CApplicationRequest(
        override val call: CApplicationCall,
        val request: HttpRequest,
        private val socket: ReadWriteSocket,
        private val buffer: ByteBuffer
) : BaseApplicationRequest() {
    override val cookies by lazy { RequestCookies(this) }

    override val headers: ValuesMap = LazyValuesMap(request)

    override val local = CConnectionPoint(request)

    override val queryParameters by lazy { parseQueryString(request.uri.substringAfter('?')) }

    override fun getMultiPartData(): MultiPartData {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private var readChannel: ReadChannel? = null

    override fun getReadChannel(): ReadChannel {
        if (readChannel != null) return readChannel!!

        if (!request.method.bodyExpected) return EmptyReadChannel
        if ("chunked" in headers[HttpHeaders.TransferEncoding].orEmpty()) {
            // TODO parse better
            return ChunkedRequestReadChannel(socket, buffer).also { readChannel = it }
        }
        val length = headers[HttpHeaders.ContentLength]?.toLong()
        if (length == null || length == 0L) return EmptyReadChannel

        return LimitedRequestReadChannel(buffer, socket, length).also { readChannel = it }
    }
}