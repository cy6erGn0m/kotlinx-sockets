package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import java.net.*
import java.nio.*
import java.util.logging.*

fun main(args: Array<String>) {
    runBlocking {
        SelectorManager().use { selector ->
            selector.serverSocket().use { server ->
                server.bind(InetSocketAddress(9096))

                while (true) {
                    var client: AsyncSocket? = null
                    try {
                        client = server.accept()
                        launch(CommonPool) {
                            client!!.use {
                                runClient(it)
                            }
                        }
                    } catch (t: Throwable) {
                        Logger.getLogger("acceptor").log(Level.SEVERE, "Failed to handle client", t)
                        client?.close()
                    }
                }
            }
        }
    }
}

private suspend fun runClient(client: AsyncSocket) {
    val input = client.asCharChannel().buffered()
    val output = client.asCharWriteChannel()
    val logger = Logger.getLogger("client")

    hello@while (true) {
        when (input.readLine()) {
            null -> return
            "" -> {}
            "HELLO" -> {
                output.write("EHLLO\n")
                break@hello
            }
            else -> {
                logger.warning("Wrong hello from client ${client.remoteAddress}")
                output.write("ERROR Wrong HELLO\n")
                return
            }
        }
    }

    command@while (true) {
        val command = input.readLine() ?: return
        when (command) {
            "" -> continue@command
            "SUM" -> sum(input, output)
            "AVG" -> avg(input, output)
            "BYE" -> {
                output.write("BYE\n")
                return
            }
            else -> {
                logger.warning("Unknown command $command from client ${client.remoteAddress}")
                output.write("ERROR Unknown command\n")
                return
            }
        }
    }
}

private suspend fun sum(input: BufferedCharReadChannel, output: CharWriteChannel) {
    val numbers = numbers(input) ?: return
    output.write("${numbers.sum()}\n")
}

private suspend fun avg(input: BufferedCharReadChannel, output: CharWriteChannel) {
    val numbers = numbers(input) ?: return
    output.write("${numbers.average()}\n")
}

private suspend fun numbers(input: BufferedCharReadChannel): List<Int>? {
    return input.readLine()?.trim()?.split(",")?.filter(String::isNotBlank)?.map(String::toInt)
}
