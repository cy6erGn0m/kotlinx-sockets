package kotlinx.http.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.http.*
import kotlinx.sockets.*
import kotlinx.sockets.ServerSocket
import org.junit.*
import java.net.*
import kotlin.test.*

class IntegrationTest {
    private var port = 0
    private val server = CompletableDeferred<ServerSocket>()
    private var handler: suspend (Request, ByteReadChannel, ByteWriteChannel) -> Unit = { r, _, o ->
        respond404(r, o)
        o.close()
    }

    @Before
    fun setUp() {
        val (j, s) = httpServer(0) { request, input, output, _ ->
            if (request.uri.toString() == "/do" && request.method == HttpMethod.POST) {
                handler(request, input, output)
            } else {
                respond404(request, output)
            }
        }

        s.invokeOnCompletion { t ->
            if (t != null) server.completeExceptionally(t)
            else server.complete(s.getCompleted())
        }

        j.invokeOnCompletion {
            s.invokeOnCompletion { t ->
                if (t != null && !s.isCancelled) {
                    s.getCompleted().close()
                }
            }
        }

        runBlocking {
            port = (s.await().localAddress as InetSocketAddress).port
        }
    }

    @After
    fun tearDown() {
        server.invokeOnCompletion { t ->
            if (t != null) {
                server.getCompleted().close()
            }
        }
    }

    @Test
    fun testChunkedRequestResponse() {
        val url = URL("http://localhost:$port/do")

        handler = { r, i, o ->
            val rr = RequestResponseBuilder()
            try {
                rr.responseLine(r.version, 200, "OK")
                rr.headerLine("Connection", "close")
                rr.headerLine("Transfer-Encoding", "chunked")
                rr.emptyLine()
                rr.writeTo(o)
                o.flush()
            } finally {
                rr.release()
            }

            val request = i //decodeChunked(i)
            val response = ByteChannel()
            val chunkerJob = launch(ioCoroutineDispatcher) {
                try {
                    encodeChunked(response, o)
                } catch (t: Throwable) {
                    o.close(t)
                    throw t
                } finally {
                    o.close()
                }
            }

            request.copyAndClose(response)
            chunkerJob.join()
        }

        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.allowUserInteraction = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            connection.requestMethod = "POST"
            connection.setChunkedStreamingMode(0)

            connection.doInput = true
            connection.doOutput = true

            connection.outputStream.use {
                it.apply {
                    write("123\n".toByteArray())
                    flush()
                    write("456\n".toByteArray())
                    flush()
                    write("abc\n".toByteArray())
                    flush()
                }
            }

            val text = connection.inputStream.reader().readText()
            assertEquals("123\n456\nabc\n", text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun respond404(request: Request, output: ByteWriteChannel) {
        val rr = RequestResponseBuilder()
        try {
            rr.responseLine(request.version, 404, "Not found")
            rr.headerLine("Connection", "close")
            rr.headerLine("Content-Length", "0")
            rr.emptyLine()
            rr.writeTo(output)
            output.flush()
        } finally {
            rr.release()
        }
    }
}