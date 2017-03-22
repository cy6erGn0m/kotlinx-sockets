package kotlinx.sockets.benchmarks

import kotlinx.sockets.*
import kotlinx.sockets.examples.numbers.*
import org.openjdk.jmh.annotations.*
import java.net.*
import java.util.concurrent.*

@State(Scope.Benchmark)
open class NumbersClientBenchmark {
    private lateinit var server: AsyncServerSocket

    @Setup
    fun setup() {
        val l = CountDownLatch(1)
        server = startNumbersServer(null) { l.countDown(); }.apply {
            l.await()
            println("server port ${(this.localAddress as InetSocketAddress).port}")
        }
    }

    @TearDown
    fun stop() {
        server.close()
    }

    @Benchmark
    fun testMe() = numbersClient((server.localAddress as InetSocketAddress).port, false)
}

fun main(args: Array<String>) {
    benchmark(args) {
        run<NumbersClientBenchmark>()
    }
}
