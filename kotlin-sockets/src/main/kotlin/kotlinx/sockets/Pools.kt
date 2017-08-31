package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.internal.*
import kotlinx.sockets.impl.ObjectPool
import kotlinx.sockets.impl.ObjectPoolImpl
import java.util.concurrent.*
import java.util.concurrent.atomic.*

val ioGroup = ThreadGroup("io-pool-group")
val selectorsGroup = ThreadGroup("selector-pool-group")

private val cpuCount = Runtime.getRuntime().availableProcessors()

val ioCoroutineDispatcher = IOCoroutineDispatcher(maxOf(2, (cpuCount * 2 / 3)))

private val selectorsPool = ThreadPoolExecutor(
        0, Int.MAX_VALUE,
        10L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(100),
        GroupThreadFactory(selectorsGroup, true))

internal val selectorsCoroutineDispatcher = selectorsPool.asCoroutineDispatcher()

private class GroupThreadFactory(val group: ThreadGroup, val isDaemon: Boolean) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(r: Runnable?): Thread {
        return Thread(group, r, group.name + counter.incrementAndGet()).apply {
            isDaemon = this@GroupThreadFactory.isDaemon
        }
    }
}

internal val DefaultByteBufferPool = DirectByteBufferPool(4096, 2048)
internal val DefaultDatagramByteBufferPool = DirectByteBufferPool(65536, 2048)

internal class DirectByteBufferPool(val bufferSize: Int, size: Int) : ObjectPoolImpl<ByteBuffer>(size) {
    override fun produceInstance(): ByteBuffer = java.nio.ByteBuffer.allocateDirect(bufferSize)

    override fun clearInstance(instance: ByteBuffer): ByteBuffer {
        instance.clear()
        return instance
    }

    override fun validateInstance(instance: ByteBuffer) {
        require(instance.isDirect)
        require(instance.capacity() == bufferSize)
    }
}