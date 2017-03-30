package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.channels.*
import java.io.*
import java.net.*

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

interface AConnectedSocket : WriteChannel {
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

interface ReadWriteSocket : ASocket, ReadWriteChannel

interface Socket : ReadWriteSocket, ABoundSocket, AConnectedSocket

interface ServerSocket : ASocket, ABoundSocket, Acceptable<Socket>

interface BoundDatagramSocket : ASocket, ABoundSocket, ReadChannel, DatagramReadWriteChannel

interface ConnectedDatagramSocket : ASocket, ABoundSocket, AConnectedSocket, ReadWriteSocket, DatagramReadWriteChannel

