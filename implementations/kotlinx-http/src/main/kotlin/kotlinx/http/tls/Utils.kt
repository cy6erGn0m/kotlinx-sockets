package kotlinx.http.tls

import kotlinx.coroutines.experimental.io.packet.*
import kotlinx.sockets.*

private fun ByteReadPacket.copyTo(dst: ByteWritePacket) {
    val buffer = DefaultByteBufferPool.borrow()
    try {
        while (true) {
            buffer.clear()
            val rc = readAvailable(buffer)
            if (rc == -1) break
            buffer.flip()
            dst.writeFully(buffer)
        }
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}

internal fun ByteWritePacket.writePacket(p: ByteReadPacket) {
    p.copyTo(this)
}


internal fun ByteReadPacket.duplicate(): Pair<ByteReadPacket, ByteReadPacket> {
    val buffer = DefaultByteBufferPool.borrow()
    val p1 = WritePacket()
    val p2 = WritePacket()

    try {
        while (true) {
            buffer.clear()
            val rc = readAvailable(buffer)
            if (rc == -1) break
            buffer.flip()
            buffer.mark()
            p1.writeFully(buffer)
            buffer.reset()
            p2.writeFully(buffer)
        }

        return Pair(p1.build(), p2.build())
    } catch (t: Throwable) {
        p1.release()
        p2.release()
        throw t
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}
