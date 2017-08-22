package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.channels.*
import java.io.*
import java.net.*
import java.nio.charset.*

/**
 * Base type for all async sockets
 */
interface ASocket : Closeable, DisposableHandle {
    override fun dispose() {
        try {
            close()
        } catch (ignore: Throwable) {
        }
    }
}

interface AConnectedSocket : AWritable {
    /**
     * Remote socket address. Could throw an exception if the peer is not yet connected or already disconnected.
     */
    val remoteAddress: SocketAddress
}

interface ABoundSocket {
    /**
     * Local socket address. Could throw an exception if no address bound yet.
     */
    val localAddress: SocketAddress
}

/**
 * Represents a socket source, for example server socket
 */
interface Acceptable<out S : ASocket> : ASocket {
    /**
     * accepts socket connection or suspends if none yet available.
     * @return accepted socket
     */
    suspend fun accept(): S
}

interface AReadable {
    fun attachForReading(channel: ByteChannel)
}

interface AWritable {
    fun attachForWriting(channel: ByteChannel)
}

interface ReadWriteSocket : ASocket, AReadable, AWritable

fun AReadable.openReadChannel(): ByteReadChannel = ByteChannel(true).also { attachForReading(it) }
fun AWritable.openWriteChannel(autoFlush: Boolean = false): ByteWriteChannel = ByteChannel(autoFlush).also { attachForWriting(it) }

@Deprecated("Use openReadChannel instead", ReplaceWith("openReadChannel()", "kotlinx.sockets.openReadChannel"))
fun AReadable.openReceiveChannel(pool: Channel<ByteBuffer>): ByteReadChannel {
    return openReadChannel()
}

@Deprecated("Use openWriteChannel instead", ReplaceWith("openWriteChannel()", "kotlinx.sockets.openWriteChannel"))
fun AWritable.openSendChannel(pool: Channel<ByteBuffer>): ByteWriteChannel {
    return openWriteChannel()
}

@Deprecated("Create a writable channel first and then invoke writeLazy/writeFully on it", level = DeprecationLevel.ERROR)
suspend fun AWritable.write(src: ByteBuffer) {
    throw UnsupportedOperationException("Not supported anymore")
}

@Deprecated("Create a readable channel first and then invoke readAvailable/readFully on it", level = DeprecationLevel.ERROR)
suspend fun AReadable.read(dst: ByteBuffer): Int {
    throw UnsupportedOperationException("Not supported anymore")
}

@Deprecated("It is not clear is it good or not")
fun AWritable.openTextSendChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ActorJob<CharSequence> {
    return openWriteChannel(true).openTextSendChannel(charset, pool, capacity)
}

@Deprecated("It is not clear is it good or not")
fun AReadable.openTextReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    return openReadChannel().openTextReceiveChannel(charset, pool, capacity)
}

@Deprecated("Pool and charset specification is not supported anymore", level = DeprecationLevel.ERROR)
fun AReadable.openLinesReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    TODO()
}

interface Socket : ReadWriteSocket, ABoundSocket, AConnectedSocket

interface ServerSocket : ASocket, ABoundSocket, Acceptable<Socket>

interface BoundDatagramSocket : ASocket, ABoundSocket, AReadable, DatagramReadWriteChannel

interface ConnectedDatagramSocket : ASocket, ABoundSocket, AConnectedSocket, ReadWriteSocket, DatagramReadWriteChannel

