package kotlinx.sockets.examples

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import java.net.*
import java.util.concurrent.*
import kotlin.system.*

fun main(args: Array<String>) {
    runBlocking {
        aSocket().tcp().connect(InetSocketAddress(9098)).use { socket ->
            println("Connected")
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel()

            while (true) {
                val line = input.readUTF8Line(limit = 8192) ?: break
                processCommand(line, socket, output)
            }
        }
    }
}

private suspend fun processCommand(line: String, socket: Socket, output: ByteWriteChannel) {
    when {
        line.startsWith("exit") -> {
            println("Got exit")
            output.writeStringUtf8("Bye.\n\n")
            output.close()
            delay(500, TimeUnit.MILLISECONDS)
            exitProcess(0)
        }
        line.startsWith("id") -> {
            output.writeStringUtf8("ID: ${socket.remoteAddress}\n")
        }
        else -> {
            output.writeStringUtf8("Unknown command: $line\n")
        }
    }

    output.flush()
}

