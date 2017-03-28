package kotlinx.sockets.examples.crawler

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*

class RequestSender(val urls: ReceiveChannel<ConnectionRequest>, val connections: SendChannel<Connection>) {

    suspend fun connectAndRequestLoop(selector: SelectorManager) {
        while (true) {
            val r = urls.receiveOrNull() ?: break

            launch(CommonPool) {
                println(r.url)
                connections.send(selector.openURL(URL(r.url), r.depth))
            }
        }

        connections.close()
    }

    private suspend fun SelectorManager.openURL(url: URL, depth: Int): Connection {
        require(url.protocol == "http") { "only plain http is supported but got $url" }

        // dns resolution is blocking here
        val address = InetSocketAddress(url.host, url.port.takeIf { it != -1 } ?: url.defaultPort.takeIf { it != -1 } ?: 80)

        val socket = socket().apply {
            setOption(StandardSocketOptions.TCP_NODELAY, true)
        }.connect(address)

        val request = buildString(256) {
            append("GET "); append(url.file); append(" HTTP/1.1\r\n")
            append("Host: "); append(url.host); if (url.port != -1) {
            append(":"); append(url.port)
        }; append("\r\n")
            append("Accept: text/html\r\n")
            append("User-Agent: crawler\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        socket.writeFully(ByteBuffer.wrap(request.toByteArray(Charsets.ISO_8859_1)))

        return Connection(url, socket, depth)
    }
}