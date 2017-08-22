package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.ServerSocket
import kotlinx.sockets.Socket
import java.net.*
import java.nio.channels.*
import java.util.logging.*

fun main(args: Array<String>) {
    val (_, job) = startNumbersServer(9096)

    runBlocking {
        job.join()
    }
}

fun startNumbersServer(port: Int?, onBound: () -> Unit = {}): Pair<ServerSocket, Job> {
    val server = aSocket().tcp().bind(port?.let(::InetSocketAddress))

    val serverJob = launch(CommonPool) {
        onBound()
        while (true) {
            var client: Socket? = null
            try {
                client = server.accept()
                launch(CommonPool) {
                    client!!.use {
                        runClient(it)
                    }
                }
            } catch (expected: ClosedChannelException) {
                client?.close()
                break
            } catch (t: Throwable) {
                Logger.getLogger("acceptor").log(Level.SEVERE, "Failed to handle client", t)
                client?.close()
            }
        }
    }.apply {
        invokeOnCompletion {
            server.close()
        }
    }

    return Pair(server, serverJob)
}

private suspend fun runClient(client: Socket) {
    val input = client.openReadChannel()
    val output = client.openWriteChannel(true)
    val logger = Logger.getLogger("client")

    hello@ while (true) {
        when (input.readASCIILine()) {
            null -> return
            "" -> {
            }
            "HELLO" -> {
                output.writeStringUtf8("EHLLO\n")
                break@hello
            }
            else -> {
                logger.warning("Wrong hello from client ${client.remoteAddress}")
                output.writeStringUtf8("ERROR Wrong HELLO\n")
                return
            }
        }
    }

    command@ while (true) {
        val command = input.readASCIILine() ?: return
        when (command) {
            "" -> continue@command
            "SUM" -> sum(input, output)
            "AVG" -> avg(input, output)
            "BYE" -> {
                output.writeStringUtf8("BYE\n")
                return
            }
            else -> {
                logger.warning("Unknown command $command from client ${client.remoteAddress}")
                output.writeStringUtf8("ERROR Unknown command\n")
                return
            }
        }
    }
}

private suspend fun sum(input: ByteReadChannel, output: ByteWriteChannel) {
    val numbers = numbers(input) ?: return
    output.writeStringUtf8("${numbers.sum()}\n")
}

private suspend fun avg(input: ByteReadChannel, output: ByteWriteChannel) {
    val numbers = numbers(input) ?: return
    output.writeStringUtf8("${numbers.average()}\n")
}

private suspend fun numbers(input: ByteReadChannel): List<Int>? {
    return input.readASCIILine()?.trim()?.split(",")?.filter(String::isNotBlank)?.map(String::toInt)
}
