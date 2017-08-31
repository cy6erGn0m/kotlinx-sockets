package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.nio.channels.*

internal fun attachForWritingImpl(channel: ByteChannel, nioChannel: WritableByteChannel, selectable: Selectable, selector: SelectorManager, pool: ObjectPool<ByteBuffer>): ReaderJob {
    val buffer = pool.borrow()

    return reader(ioCoroutineDispatcher, channel) {
        try {
            while (true) {
                buffer.clear()
                if (channel.readAvailable(buffer) == -1) {
                    break
                }
                buffer.flip()

                while (buffer.hasRemaining()) {
                    val rc = nioChannel.write(buffer)
                    if (rc == 0) {
                        selectable.interestOp(SelectInterest.WRITE, true)
                        selector.select(selectable, SelectInterest.WRITE)
                    } else {
                        selectable.interestOp(SelectInterest.WRITE, false)
                    }
                }
            }
        } finally {
            pool.recycle(buffer)
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.shutdownOutput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}