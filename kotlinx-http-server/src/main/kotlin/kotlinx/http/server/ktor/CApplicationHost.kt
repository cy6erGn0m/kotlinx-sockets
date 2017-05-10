package kotlinx.http.server.ktor

import kotlinx.coroutines.experimental.*
import kotlinx.http.server.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import java.net.*
import java.nio.*
import java.util.concurrent.*

class CApplicationHost(environment: ApplicationHostEnvironment) : BaseApplicationHost(environment) {
    private val ioPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)
    private val taskPool = Executors.newFixedThreadPool(200)

    private val ioDispatcher = ioPool.asCoroutineDispatcher()
    private val taskDispatcher = taskPool.asCoroutineDispatcher()

    private val bufferSize = 8192
    private val buffersCount = 100000
    private val bufferPool = ArrayBlockingQueue<ByteBuffer>(buffersCount)

    private var jobs: List<Job> = emptyList()
    private var manager: ExplicitSelectorManager? = null

    override fun start(wait: Boolean): ApplicationHost {
        environment.start()

        for (i in 1..buffersCount) {
            bufferPool.put(ByteBuffer.allocate(bufferSize))
        }

        val selectors = ExplicitSelectorManager()
        manager = selectors

        jobs = environment.connectors.map {
            if (it.type != ConnectorType.HTTP) throw IllegalStateException("Only plain HTTP connectors supported")

            launch(ioDispatcher) {
                aSocket(selectors).tcp().bind(InetSocketAddress(it.port)).use { server ->
                    while (true) {
                        try {
                            val client = server.accept()
                            launch(ioDispatcher) {
                                client.use {
                                    handleClient(client)
                                }
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        while (wait && !ioPool.isTerminated) {
            ioPool.awaitTermination(100L, TimeUnit.DAYS)
        }

        return this
    }

    private suspend fun handleClient(client: Socket) {
        val bb = bufferPool.poll() ?: ByteBuffer.allocate(bufferSize)
        val headersBuffer = bufferPool.poll() ?: ByteBuffer.allocate(bufferSize)
        bb.clear()

        try {
            loop@ while (true) {
                val parser = HttpParser(bb, headersBuffer)
                val request = parser.parse(client) ?: break@loop

                launch(taskDispatcher) {
                    pipeline.execute(CApplicationCall(environment.application, request, client, bb, bufferPool))
                }.join()

                val connection = request.headerFirst(HttpHeaders.Connection)
                if (connection != null && connection.valueLength == 5 && connection.value(request.headersBody).equals("close", ignoreCase = true)) {
                    break@loop
                }
            }
        } finally {
            bufferPool.offer(bb)
            bufferPool.offer(headersBuffer)
        }
    }

    // TODO handle default connection: keep-alive
    private fun defaultConnectionForVersion(version: String) = if (version == "HTTP/1.1") "keep-alive" else "close"

    override fun stop(gracePeriod: Long, timeout: Long, timeUnit: TimeUnit) {
        // TODO gracefull shutdown
        taskPool.shutdown()
        taskPool.awaitTermination(gracePeriod, timeUnit)

        jobs.forEach { it.cancel() }

        ioPool.shutdown()
        ioPool.awaitTermination(gracePeriod, timeUnit)

        manager?.let {
            it.close()
            manager = null
        }

        environment.stop()
    }

    object Factory : ApplicationHostFactory<CApplicationHost> {
        override fun create(environment: ApplicationHostEnvironment) = CApplicationHost(environment)
    }
}
