package kotlinx.sockets.examples.http

import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*

suspend fun handleRequest2(request: Request, input: ByteReadChannel, output: ByteWriteChannel) {
//    request.headers.forEachHeader { name, value -> println("$name => $value") }

    try {
        output.writePacket(buildPacket {
            append("HTTP/1.1 200 OK\r\n")
            append("Connection: keep-alive\r\n")
            append("Content-Type: text-plain\r\n")
            append("Content-Length: 13\r\n")
            append("\r\n")
            append("Hello, World\n")
        })
        output.flush()
    } finally {
        request.headers.release()
    }
}