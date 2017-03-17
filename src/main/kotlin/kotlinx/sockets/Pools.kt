package kotlinx.sockets

import java.util.concurrent.*
import java.util.concurrent.atomic.*

private val group = ThreadGroup("pool-group")
private val counter = AtomicInteger()
internal val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2 + 1) { r ->
    Thread(group, r, group.name + counter.incrementAndGet()).apply {
        isDaemon = true
    }
}

internal fun ensureOnPool(block: () -> Unit) {
    if (Thread.currentThread().threadGroup == group) {
        block()
    } else {
        pool.execute(block)
    }
}

