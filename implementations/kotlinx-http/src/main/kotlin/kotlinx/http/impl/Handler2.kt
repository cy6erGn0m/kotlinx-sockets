package kotlinx.http.impl

import kotlinx.coroutines.experimental.io.*

suspend fun handleRequest2(request: Request, input: ByteReadChannel, output: ByteWriteChannel) {
//    request.headers.forEachHeader { name, value -> println("$name => $value") }

    val response = RequestResponseBuilder()

    try {
        response.responseLine("HTTP/1.1", 200, "OK")
        response.headerLine("Connection", "keep-alive")
        response.headerLine("Content-Type", "text/plain")
        response.headerLine("Content-Length", "14")
        response.emptyLine()
        response.line("Hello, World")

        response.writeTo(output)
        output.flush()
    } finally {
        request.release()
        response.release()
    }
}