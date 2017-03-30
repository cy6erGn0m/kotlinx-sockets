package kotlinx.sockets.benchmarks

import io.netty.channel.*
import kotlinx.sockets.ServerSocket
import kotlinx.sockets.examples.numbers.*
import org.openjdk.jmh.annotations.*
import java.net.*
import java.util.concurrent.*

@State(Scope.Benchmark)
open class NumbersClientBenchmark {
    private lateinit var server: ServerSocket
    private lateinit var nettyServer: Channel

    @Setup
    fun setup() {
        val l = CountDownLatch(1)
        val (s, _) = startNumbersServer(null) { l.countDown(); }
        l.await()
        println("server port ${(s.localAddress as InetSocketAddress).port}")

        server = s

        nettyServer = NettyNumbersServer.start(null, false)
    }

    @TearDown
    fun stop() {
        try {
            server.close()
        } finally {
            nettyServer.close()
        }
    }

    @Benchmark
    fun testKotlin() = numbersClient((server.localAddress as InetSocketAddress).port, false)

    @Benchmark
    fun testNetty() = NettyNumbersClient.start((nettyServer.localAddress() as InetSocketAddress).port, false).closeFuture().sync()!!
}

fun main(args: Array<String>) {
    benchmark(args) {
        run<NumbersClientBenchmark>()
    }
}
