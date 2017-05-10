package kotlinx.http.server

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.channels.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.util.concurrent.*

private val bufferSize = 8192
private val buffersCount = 100000
private val bufferPool = ArrayBlockingQueue<ByteBuffer>(buffersCount)

fun main(args: Array<String>) {
    for (i in 1..buffersCount) {
        bufferPool.put(ByteBuffer.allocate(bufferSize))
    }

    runBlocking {
        aSocket(ExplicitSelectorManager()).tcp().bind(InetSocketAddress(8080)).use { server ->
            while (true) {
                try {
                    val client = server.accept()
                    launch(CommonPool) {
                        client.use {
                            handleClient(client)
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}

private suspend fun handleClient(client: Socket) {
    val bb = bufferPool.poll() ?: ByteBuffer.allocate(bufferSize)
    val hb = bufferPool.poll() ?: ByteBuffer.allocate(bufferSize)

    try {
        loop@ while (true) {
            val parser = HttpParser(bb, hb)
            val request = parser.parse(client) ?: break@loop

            when {
                request.method !== HttpMethod.Get -> {
                    client.respond(405, "Method Not Allowed", request.version, "close", "Not allowed: ${request.method}")
                    break@loop
                }
                request.uri != "/" -> {
                    val connection = request.header("Connection").singleOrNull()?.value(request.headersBody) ?: defaultConnectionForVersion(request.version)

                    client.respond(404, "Not Found", request.version, connection, "Not found: ${request.uri}")

                    if (connection.equals("close", ignoreCase = true)) {
                        break@loop
                    }
                }
                else -> {
                    val connection = request.header("Connection").singleOrNull()?.value(request.headersBody) ?: defaultConnectionForVersion(request.version)

                    client.respond(200, "OK", request.version, connection, "Hello, World!")

                    if (connection.equals("close", ignoreCase = true)) {
                        break@loop
                    }
                }
            }
        }
    } finally {
        bufferPool.offer(bb)
        bufferPool.offer(hb)
    }
}

private fun defaultConnectionForVersion(version: HttpVersion) = if (version == HttpVersion.HTTP11) "keep-alive" else "close"

private suspend fun WriteChannel.respond(code: Int, statusMessage: String, version: HttpVersion, connection: String, content: String) {
    write(ByteBuffer.wrap(buildString(256) {
        append(version.text)
        append(' ')
        append(code)
        append(' ')
        append(statusMessage)
        append("\r\n")

        append("Connection: "); append(connection); append("\r\n")
        append("Content-Type: text/plain\r\n")
        append("Content-Length: "); append((content.length + 2).toString()); append("\r\n")
        append("\r\n")
        append(content)
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)))
}
