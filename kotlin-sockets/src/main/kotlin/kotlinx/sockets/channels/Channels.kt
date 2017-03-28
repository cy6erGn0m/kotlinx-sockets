package kotlinx.sockets.channels

import kotlinx.sockets.*
import java.io.*
import java.net.*
import java.nio.*

/**
 * Represents a readable channel
 */
interface ReadChannel {
    /**
     * Reads bytes from the channel to the given [dst] byte buffer. Suspends if no bytes available yet and
     * no end of stream reached.
     * @return number of bytes read or -1 if the channel is over. Updates buffer's position.
     */
    suspend fun read(dst: ByteBuffer): Int
}

/**
 * Represents output that could be shut down.
 */
interface Output {
    /**
     * Shut down output. It generally doesn't close related socket but just shut downs output channel
     * so the remote peer may receive end of stream.
     * Please note that this has no effect for datagram outputs.
     */
    fun shutdownOutput()
}

/**
 * Represents a writable channel
 */
interface WriteChannel : Output {
    /**
     * Writes bytes from the [src] byte buffer to the channel. Returns when only part or all of bytes were written
     * and updates buffer's position accordingly. Suspends if no bytes could be written immediately.
     */
    suspend fun write(src: ByteBuffer)
}

interface DatagramWriteChannel : Output {
    suspend fun write(src: ByteBuffer, target: SocketAddress)
}

interface DatagramReadChannel {
    suspend fun receive(dst: ByteBuffer): SocketAddress
}

/**
 * Represents a socket source, for example server socket
 */
interface SocketSource<out S : Closeable> : ASocket {
    /**
     * accepts socket connection or suspends if none yet available.
     * @return accepted socket
     */
    suspend fun accept(): S
}

/**
 * Represents a character channel from one can read
 */
interface CharReadChannel {
    /**
     * Reads characters to the given [dst] buffer, suspends when no characters available yet
     * or returns -1 if end of stream reached.
     */
    suspend fun read(dst: CharBuffer): Int
}

/**
 * Represents a character channel to which one can write characters
 */
interface CharWriteChannel : Output {
    /**
     * Writes characters from given [src] to the channel
     */
    suspend fun write(src: CharBuffer)
}

/**
 * Represents a buffered channel from one can read characters as from [CharReadChannel], [read] a single character
 * or [readLine].
 */
interface BufferedCharReadChannel : CharReadChannel {
    /**
     * Reads single character from the channel. Could suspend if the internal buffer is empty and no
     * characters available yet
     * @return a character or -1 if end of stream reached
     */
    suspend fun read(): Int

    /**
     * Reads line from the channel. Uses [StringBuilder] as line buffer and the internal buffer. Reads and suspends
     * when needed until end of line reached. Internally uses [CharReadChannel.readLineTo] implementation.
     */
    suspend fun readLine(): String?
}
