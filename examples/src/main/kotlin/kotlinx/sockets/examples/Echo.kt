package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import java.net.*

fun main(args: Array<String>) {
    runBlocking {
        aSocket().tcp().bind(InetSocketAddress(9094)).use { server ->
            while (true) {
                runClient(server.accept())
            }
        }
    }
}

private fun runClient(client: Socket) {
    launch(CommonPool) {
        client.use {
            val input = it.openReadChannel()
            val output = it.openWriteChannel(true)

            input.copyAndClose(output)
        }
    }
}