package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*

fun main(args: Array<String>) {
    val urlString = args.firstOrNull() ?: "https://kotlinlang.org"
    val url = URL(urlString)

    if (url.protocol != "https") throw IllegalArgumentException("Only https is supported")

    val host = url.host
    val port = url.port.takeIf { it > 0 }?.toInt() ?: 443
    val pathAndQuery = (url.path + (url.query?.let { "?" + it } ?: "")).trim().let { if (it.startsWith("/")) it else "/$it" }

    val remoteAddress = InetSocketAddress(host, port)

    runBlocking {
        ActorSelectorManager().use { selector ->
            aSocket(selector).tcp().connect(remoteAddress).use { socket ->
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()

                val session = TLSClientSession(input, output, serverName = host)
                launch(CommonPool) {
                    session.run()
                }

                session.appDataOutput.writeStringUtf8("GET $pathAndQuery HTTP/1.1\r\nHost: $host:$port\r\nAccept: */*\r\nUser-Agent: kotlinx-tls-client\r\nConnection: close\r\n\r\n")
                session.appDataOutput.flush()

                val bb = ByteBuffer.allocate(8192)
                while (true) {
                    val rc = session.appDataInput.readAvailable(bb)
                    if (rc == -1) break
                    bb.flip()
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), rc)
                    System.out.flush()
                }
            }
        }
    }
}
