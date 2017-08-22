package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.nio.channels.*

internal fun attachForWritingImpl(channel: ByteChannel, nioChannel: WritableByteChannel, selectable: SelectableBase, selector: SelectorManager) {

    val buffer = ByteBuffer.allocateDirect(65536)

    launch(ioCoroutineDispatcher) {
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
        } catch (t: Throwable) {
            channel.close(t)
        } finally {
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.shutdownOutput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}