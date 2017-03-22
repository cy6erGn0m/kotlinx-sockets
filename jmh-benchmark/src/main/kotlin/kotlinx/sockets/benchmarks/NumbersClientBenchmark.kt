package kotlinx.sockets.benchmarks

import io.netty.channel.*
import kotlinx.sockets.*
import kotlinx.sockets.examples.numbers.*
import org.openjdk.jmh.annotations.*
import java.net.*
import java.util.concurrent.*

@State(Scope.Benchmark)
open class NumbersClientBenchmark {
    private lateinit var server: AsyncServerSocket
    private lateinit var nettyServer: Channel

    @Setup
    fun setup() {
        val l = CountDownLatch(1)
        server = startNumbersServer(null) { l.countDown(); }.apply {
            l.await()
            println("server port ${(this.localAddress as InetSocketAddress).port}")
        }

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
