package kotlinx.http.impl

import kotlinx.coroutines.experimental.io.*

suspend fun handleRequest2(request: Request, input: ByteReadChannel, output: ByteWriteChannel) {
    try {
        if (request.uri.startsWith("/post")) {
            if (request.method != HttpMethod.POST) {
                respondText(output, 405, "Method not allowed", "Method ${request.method} not allowed\r\n")
                return
            }

            val requestBody = input.readAll()
            try {
                val size = requestBody.remaining
                println(requestBody.inputStream().reader().readText())

                respondText(output, 200, "OK", "$size bytes were received\r\n")
            } finally {
                requestBody.release()
            }
        } else if (request.uri == "/") {
            if (request.method != HttpMethod.GET) {
                respondText(output, 405, "Method not allowed", "Method ${request.method} not allowed\r\n")
                return
            }
            respondText(output, 200, "OK", "Hello, World!\r\n")
        } else {
            respondText(output, 404, "Not found", "Object not found at uri ${request.uri}\r\n")
        }
    } finally {
        request.release()
    }
}

private suspend fun respondText(output: ByteWriteChannel, statusCode: Int, statusText: String, body: CharSequence) {
    val response = RequestResponseBuilder()

    try {
        response.responseLine("HTTP/1.1", statusCode, statusText)
        response.headerLine("Connection", "keep-alive")
        response.headerLine("Content-Type", "text/plain")
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