package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import java.io.*
import java.net.*
import java.util.*
import kotlin.system.*

fun main(args: Array<String>) {
    numbersClient(9096, true)
}

fun numbersClient(port: Int, log: Boolean): Long {
    return runBlocking(CommonPool) {
        numbersClientImpl(port, log)
    }
}

suspend fun numbersClientImpl(port: Int, log: Boolean): Long {
    return aSocket().tcp().tcpNoDelay().configure { this[StandardSocketOptions.SO_LINGER] = 1 }.connect(InetSocketAddress(port)).use { socket ->
        if (log) {
            println("Connected")
        }

        val time = measureTimeMillis {
            main(socket.openReadChannel(), socket.openWriteChannel(), log)
        }

        if (log) {
            println("time: $time ms")
        }

        time
    }
}

private suspend fun main(input: ByteReadChannel, output: ByteWriteChannel, log: Boolean) {
    output.writeStringUtf8("HELLO\n")
    output.flush()

    when (input.readASCIILine()) {
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

    output.writeStringUtf8("BYE\n")
    do {
        when (input.readASCIILine()) {
            "BYE", null -> return
            else -> {
            }
        }
    } while (true)
}

private suspend fun sum(input: ByteReadChannel, output: ByteWriteChannel, rnd: Random, log: Boolean) {
    val numbers = rnd.randomNumbers()

    output.writeStringUtf8(numbers.joinToString(",", prefix = "SUM\n", postfix = "\n"))
    output.flush()

    val response = input.readASCIILine()
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

private suspend fun avg(input: ByteReadChannel, output: ByteWriteChannel, rnd: Random, log: Boolean) {
    val numbers = rnd.randomNumbers()

    output.writeStringUtf8(numbers.joinToString(",", prefix = "AVG\n", postfix = "\n"))
    output.flush()

    val response = input.readASCIILine()
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
