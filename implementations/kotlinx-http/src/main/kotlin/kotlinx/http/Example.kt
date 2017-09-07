package kotlinx.http


import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.ServerSocket
import kotlinx.sockets.adapters.*
import java.net.*
import java.nio.channels.*


fun main(args: Array<String>) {
    runBlocking {
        exampleHttpServer(CompletableDeferred())
    }
}

fun main0(args: Array<String>) {
    runBlocking {
        aSocket().tcp().bind(InetSocketAddress(9096)).use { s ->
            s.openAcceptChannel().consumeEach { client ->
                launch(ioCoroutineDispatcher) {
                    val buffer = DefaultByteBufferPool.borrow()
                    try {
                        val ch = client.openReadChannel()
                        val out = Channels.newChannel(System.out)
                        while (true) {
                            buffer.clear()
                            val rc = ch.readAvailable(buffer)
                            if (rc == -1) break
                            buffer.flip()

                            while (buffer.hasRemaining()) {
                                out.write(buffer)
                            }

                            System.out.flush()
                        }
                    } finally {
                        DefaultByteBufferPool.recycle(buffer)
                    }
                }
            }
        }
    }
}

suspend fun exampleHttpServer(deferred: CompletableDeferred<ServerSocket>) {
    val (j, s) = httpServer { r, i, o ->
        handleRequest(r, i, o)
    }

    s.invokeOnCompletion { t ->
        if (t != null) deferred.completeExceptionally(t) else deferred.complete(s.getCompleted())
    }

    j.join()
}

private suspend fun handleRequest(request: Request, input: ByteReadChannel, output: ByteWriteChannel) {
    try {
        when {
            request.uri.startsWith("/post") -> {
                if (request.method != HttpMethod.POST) {
                    respondText(output, 405, "Method not allowed", "text/plain", "Method ${request.method} not allowed\r\n")
                    return
                }

                val requestBody = input.readRemaining()
                try {
                    val size = requestBody.remaining
                    println(requestBody.inputStream().reader().readText())

                    respondText(output, 200, "OK", "text/plain", "$size bytes were received\r\n")
                } finally {
                    requestBody.release()
                }
            }
            request.uri.startsWith("/mp") -> {
                if (request.method == HttpMethod.GET) {
                    respondText(output, 200, "OK", "text/html", """
                        <!DOCTYPE html>
                        <html>
                        <body>
                            <form method="post" action="/mp" enctype="multipart/form-data">
                                <input type="text" name="title" />
                                <input type="file" name="body" />
                            </form>
                        </body>
                        </html>
                        """.trimIndent())
                    return
                }
                if (request.method == HttpMethod.POST) {
                    println("Multipart:")
                    request.headers.dump("  ")
                    val mp = parseMultipart(input, request.headers)
                    var bytesUploaded = 0L
                    mp.consumeEach { evt ->
                        println(evt)
                        if (evt is MultipartEvent.MultipartPart) {
                            evt.headers.await().dump("  ")
                            val content = evt.body.readRemaining()
                            bytesUploaded += content.remaining
                            println("  > content: ${content.remaining}")
                            content.release()
                        }
                        evt.release()
                    }

                    respondText(output, 200, "OK", "text/plain", "Got $bytesUploaded bytes\r\n")
                    return
                }
                respondText(output, 405, "Method not allowed", "text/plain", "Method ${request.method} not allowed\r\n")
                return
            }
            request.uri == "/" -> {
                if (request.method != HttpMethod.GET) {
                    respondText(output, 405, "Method not allowed", "text/plain", "Method ${request.method} not allowed\r\n")
                    return
                }
                respondText(output, 200, "OK", "text/plain", "Hello, World!\r\n")
            }
            else -> respondText(output, 404, "Not found", "text/plain", "Object not found at uri ${request.uri}\r\n")
        }
    } finally {
        request.release()
    }
}

private suspend fun respondText(output: ByteWriteChannel, statusCode: Int, statusText: String, contentType: String, body: CharSequence) {
    val response = RequestResponseBuilder()

    try {
        response.responseLine("HTTP/1.1", statusCode, statusText)
        response.headerLine("Connection", "keep-alive")
        response.headerLine("Content-Type", contentType)
        response.headerLine("Content-Length", body.length.toString())
        response.emptyLine()
        response.writeTo(output)
        output.flush()

        output.writeStringUtf8(body)
        output.flush()
    } finally {
        response.release()
    }
}

private fun HttpHeaders.dump(indent: String) {
    for (i in 0 until size) {
        println("$indent${nameAt(i)} => ${valueAt(i)}")
    }
}