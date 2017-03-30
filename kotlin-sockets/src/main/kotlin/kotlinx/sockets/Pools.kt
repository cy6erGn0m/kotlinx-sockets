package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

val ioGroup = ThreadGroup("io-pool-group")
val selectorsGroup = ThreadGroup("selector-pool-group")

private val cpuCount = Runtime.getRuntime().availableProcessors()

private val ioPool = ThreadPoolExecutor(
        cpuCount + 1,
        cpuCount * 2 + 1, 10L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(100000),
        GroupThreadFactory(ioGroup, true)
        )

val ioCoroutineDispatcher = ioPool.asCoroutineDispatcher()

private val selectorsPool = ThreadPoolExecutor(
        0, Int.MAX_VALUE,
        10L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(100),
        GroupThreadFactory(selectorsGroup, true))

val selectorsCoroutineDispatcher = selectorsPool.asCoroutineDispatcher()

private class GroupThreadFactory(val group: ThreadGroup, val isDaemon: Boolean) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(r: Runnable?): Thread {
        return Thread(group, r, group.name + counter.incrementAndGet()).apply {
            isDaemon = this@GroupThreadFactory.isDaemon
        }
    }
}