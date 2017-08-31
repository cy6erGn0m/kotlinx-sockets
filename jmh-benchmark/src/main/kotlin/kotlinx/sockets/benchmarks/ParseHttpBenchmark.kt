package kotlinx.sockets.benchmarks

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.examples.*
import kotlinx.sockets.examples.http.*
import org.openjdk.jmh.annotations.*

@State(Scope.Benchmark)
open class ParseHttpBenchmark {
    private val serverSocket = CompletableDeferred<ServerSocket>()

    @Setup
    fun run() {
        runBlocking {
            launch(CommonPool) {
                httpServer2(serverSocket)
            }
            serverSocket.await()
        }
    }

    @Benchmark
    fun main() {
        runWrk().waitFor()
    }
}

fun runWrk(): Process {
    val p = Runtime.getRuntime().exec(arrayOf("/usr/bin/wrk", "-c", "200", "-t", "16", "-d", "10s", "--latency", "http://localhost:9096"))
    launch(CommonPool) {
        p.errorStream.copyTo(System.err)
    }
    launch(CommonPool) {
        p.inputStream.bufferedReader().readText()
//        p.inputStream.copyTo(System.out)
    }
    return p
}

fun main(args: Array<String>) {
    benchmark(args) {
        run<ParseHttpBenchmark>()
    }
}