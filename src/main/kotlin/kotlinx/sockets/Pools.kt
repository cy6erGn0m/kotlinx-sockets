package kotlinx.sockets

import java.util.concurrent.*
import java.util.concurrent.atomic.*

private val group = ThreadGroup("io-pool-group")
private val counter = AtomicInteger()
private val cpuCount = Runtime.getRuntime().availableProcessors()

internal val ioPool = ThreadPoolExecutor(cpuCount, cpuCount * 2 + 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(10), { r ->
    Thread(group, r, group.name + counter.incrementAndGet()).apply {
        isDaemon = true
    }
})
