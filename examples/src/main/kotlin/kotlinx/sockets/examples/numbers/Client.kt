package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.channels.*
import kotlinx.sockets.channels.impl.*
import kotlinx.sockets.selector.*
import java.io.*
import java.net.*
import java.util.*
import kotlin.system.*

fun main(args: Array<String>) {
    numbersClient(9096, true)
}

fun numbersClient(port: Int, log: Boolean): Long {
    return runBlocking(CommonPool) {
        SelectorManager().use { selector ->
            selector.socket().use { before ->
                before.setOption(StandardSocketOptions.TCP_NODELAY, true) // disable Nagel's
                val socket = before.connect(InetSocketAddress(port))

                if (log) {
                    println("Connected")
                }

                val time = measureTimeMillis {
                    main(socket.asCharChannel().buffered(), socket.asCharWriteChannel(), log)
                }

                if (log) {
                    println("time: $time ms")
                }

                time
            }
        }
    }
}

private suspend fun main(input: BufferedCharReadChannel, output: CharWriteChannel, log: Boolean) {
    output.write("HELLO\n")
    when (input.readLine()) {
        null -> return
        "EHLLO" -> {
        }
        else -> {
            throw IOException("Wrong server response")
        }
    }

    val rnd = Random()

    for (i in 1..200) {
        sum(input, output, rnd, log)
    }

    for (i in 1..200) {
        avg(input, output, rnd, log)
    }

    output.write("BYE\n")
    do {
        when (input.readLine()) {
            "BYE", null -> return
            else -> {
            }
        }
    } while (true)
}

private suspend fun sum(input: BufferedCharReadChannel, output: CharWriteChannel, rnd: Random, log: Boolean) {
    val numbers = rnd.randomNumbers()

    output.write("SUM\n")
    output.write(numbers.joinToString(",", postfix = "\n"))

    val response = input.readLine()
    val result = when (response) {
        null -> throw IOException("Unexpected EOF")
        else -> response.trim().toInt()
    }

    if (result != numbers.sum()) {
        throw IOException("Server response for SUM($numbers) is $result but should be ${numbers.sum()} ")
    } else if (log) {
        println("SUM($numbers) = $result")
    }
}

private suspend fun avg(input: BufferedCharReadChannel, output: CharWriteChannel, rnd: Random, log: Boolean) {
    val numbers = rnd.randomNumbers()

    output.write("AVG\n")
    output.write(numbers.joinToString(",", postfix = "\n"))

    val response = input.readLine()
    val result = when (response) {
        null -> throw IOException("Unexpected EOF")
        else -> response.trim().toDouble()
    }

    if (result != numbers.average()) {
        throw IOException("Server response for AVG($numbers) is $result but should be ${numbers.average()} ")
    } else if (log) {
        println("AVG($numbers) = $result")
    }
}

private fun Random.randomNumbers() = (1..1 + nextInt(10)).map { nextInt(50) }
