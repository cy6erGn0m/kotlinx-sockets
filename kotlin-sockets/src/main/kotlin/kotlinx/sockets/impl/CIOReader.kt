package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.nio.channels.*

internal fun attachForReadingImpl(channel: ByteChannel, nioChannel: ReadableByteChannel, selectable: SelectableBase, selector: SelectorManager) {
    val buffer = ByteBuffer.allocateDirect(65536)

    launch(ioCoroutineDispatcher) {
        try {
            while (true) {
                val rc = nioChannel.read(buffer)
                if (rc == -1) {
                    channel.close()
                    break
                } else if (rc == 0) {
                    selectable.interestOp(SelectInterest.READ, true)
                    selector.select(selectable, SelectInterest.READ)
                } else {
                    selectable.interestOp(SelectInterest.READ, false)
                    buffer.flip()
                    channel.writeFully(buffer)
                    buffer.clear()
                }
            }
        } catch (t: Throwable) {
            channel.close(t)
        } finally {
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.shutdownInput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}