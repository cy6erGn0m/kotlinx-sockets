package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*

fun main(args: Array<String>) {
//    val remoteAddress = InetSocketAddress(InetAddress.getByName("ya.ru"), 443)
    val remoteAddress = InetSocketAddress(InetAddress.getByName("localhost"), 44330)
//    val remoteAddress = InetSocketAddress(InetAddress.getByName("localhost"), 9443)

    runBlocking {
        ActorSelectorManager().use { selector ->
            aSocket(selector).tcp().connect(remoteAddress).use { socket ->
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()

                val session = TLSClientSession(input, output)
                launch(CommonPool) {
                    session.run()
                }

                session.appDataOutput.writeStringUtf8("GET / HTTP/1.1\r\nHost: localhost:44330\r\nConnection: keep-alive\r\n\r\n")
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
