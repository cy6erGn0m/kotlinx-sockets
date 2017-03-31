package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*

fun runDefaultByteBufferPool(capacity: Int = 1000, size: Int = 8192): Channel<ByteBuffer> {
    return PoolChannel(capacity) { ByteBuffer.allocate(size) }.apply { fill() }
}

private class PoolChannel(capacity: Int, val allocate: () -> ByteBuffer) : ArrayChannel<ByteBuffer>(capacity) {
    override fun onEnqueuedReceive() {
        super.onEnqueuedReceive()

        if (offer(allocate())) {
            fill()
        }
    }

    fun fill() {
        if (!isClosedForSend) {
            launch(CommonPool) {
                try {
                    while (true) {
                        if (!offer(allocate())) break
                    }
                } catch (expected: ClosedSendChannelException) {
                }
            }
        }
    }
}
