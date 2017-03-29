package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*

fun main(args: Array<String>) {
    runBlocking {
        SelectorManager().use { selector ->
            selector.aSocket().tcp().bind(InetSocketAddress(9094)).use { server ->
                while (true) {
                    runClient(server.accept())
                }
            }
        }
    }
}

private fun runClient(client: AsyncSocket) {
    launch(CommonPool) {
        client.use {
            val bb = ByteBuffer.allocateDirect(8192)
            while (true) {
                bb.clear()
                val rc = client.read(bb)
                if (rc == -1) break
                bb.flip()

                while (bb.hasRemaining()) {
                    client.write(bb)
                }
            }
        }
    }
}