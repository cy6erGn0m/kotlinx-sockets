package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.util.concurrent.*

fun runDefaultByteBufferPool(capacity: Int = 1000): Channel<ByteBuffer> {
    return runDefaultByteBufferPool(ArrayChannel<ByteBuffer>(capacity))
}

fun runDefaultByteBufferPool(pool: Channel<ByteBuffer>): Channel<ByteBuffer> {
    launch(CommonPool) {
        while (true) { // initial fill
            if (!pool.offer(ByteBuffer.allocate(8192))) break
        }

        while (true) {
            delay(10L, TimeUnit.SECONDS)

            while (true) { // add more
                if (!pool.offer(ByteBuffer.allocate(8192))) break
            }
        }
    }

    return pool
}
