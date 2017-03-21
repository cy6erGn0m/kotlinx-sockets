package kotlinx.sockets

import java.io.*
import java.nio.*

/**
 * Represents a readable channel
 */
interface ReadChannel : Closeable {
    /**
     * Reads bytes from the channel to the given [dst] byte buffer. Suspends if no bytes available yet and
     * no end of stream reached.
     * @return number of bytes read or -1 if the channel is over. Updates buffer's position.
     */
    suspend fun read(dst: ByteBuffer): Int
}

/**
 * Represents a writable channel
 */
interface WriteChannel : Closeable {
    /**
     * Writes bytes from the [src] byte buffer to the channel. Returns when only part or all of bytes were written
     * and updates buffer's position accordingly. Suspends if no bytes could be written immediately.
     */
    suspend fun write(src: ByteBuffer)
}

/**
 * Represents a socket source, for example server socket
 */
interface SocketSource : Closeable {
    /**
     * accepts socket connection or suspends if none yet available.
     * @return accepted socket
     */
    suspend fun accept(): AsyncSocket
}
