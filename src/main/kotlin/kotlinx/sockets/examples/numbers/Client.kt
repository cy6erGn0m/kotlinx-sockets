package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import java.io.*
import java.net.*
import java.util.*

fun main(args: Array<String>) {
    runBlocking {
        SelectorManager().use { selector ->
            selector.socket().use { socket ->
                socket.connect(InetSocketAddress(9096))
                println("Connected")

                main(socket.asCharChannel().buffered(), socket.asCharWriteChannel())
            }
        }
    }
}

private suspend fun main(input: BufferedCharReadChannel, output: CharWriteChannel) {
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

    for (i in 1..100) {
        sum(input, output, rnd)
    }

    for (i in 1..100) {
        avg(input, output, rnd)
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

private suspend fun sum(input: BufferedCharReadChannel, output: CharWriteChannel, rnd: Random) {
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
    } else {
        println("SUM($numbers) = $result")
    }
}

private suspend fun avg(input: BufferedCharReadChannel, output: CharWriteChannel, rnd: Random) {
    val numbers = rnd.randomNumbers()

    output.write("AVG\n")
    output.write(numbers.joinToString(",", postfix = "\n"))

    val response = input.readLine()
    val result = when (response) {
        null -> throw IOException("Unexpected EOF")
        else -> response.trim().toDouble()
    }

    if (result != numbers.average()) {
        throw IOException("Server response for AVG($numbers) is $result but should be ${numbers.sum()} ")
    } else {
        println("SUM($numbers) = $result")
    }
}

private fun Random.randomNumbers() = (1..1 + nextInt(10)).map { nextInt(50) }
