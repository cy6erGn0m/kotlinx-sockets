package kotlinx.sockets

import java.io.*
import java.nio.*

interface ReadChannel : Closeable {
    suspend fun read(dst: ByteBuffer): Int
}

interface WriteChannel : Closeable {
    suspend fun write(src: ByteBuffer)
}

interface SocketSource : Closeable {
    suspend fun accept(): AsyncSocket
}
