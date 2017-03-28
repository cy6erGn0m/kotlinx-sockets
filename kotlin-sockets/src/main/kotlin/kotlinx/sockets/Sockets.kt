package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.channels.*
import java.io.*
import java.net.*

/**
 * Base type for all async sockets
 */
interface ASocket : Closeable, DisposableHandle, AConfigurableSocket {
    override fun dispose() {
        try {
            close()
        } catch (ignore: Throwable) {
        }
    }
}

interface AConfigurableSocket {
    /**
     * Configures option [option] with given [value]. Could throw an exception if [option] is not supported by
     * the socket or [value] is not relevant or invalid.
     */
    fun <T> setOption(option: SocketOption<T>, value: T)

    fun <T> getOption(option: SocketOption<T>): T

    val supportedOptions: Set<SocketOption<*>>
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
interface AsyncAcceptable<out S : ASocket> : ASocket {
    /**
     * accepts socket connection or suspends if none yet available.
     * @return accepted socket
     */
    suspend fun accept(): S
}

interface AReadWriteSocket : ASocket, ReadWriteChannel

interface ABoundableSocket<out S : ABoundSocket> : ASocket {
    fun bind(localAddress: SocketAddress?): S
}

interface AConnectableSocket<out S : AConnectedSocket> : ASocket {
    /**
     * Connect socket to the specified [target], suspends until connection succeeds.
     */
    suspend fun connect(target: SocketAddress): S
}

interface AsyncInitialSocket : ASocket, AConnectableSocket<AsyncSocket>

/**
 * Represents a TCP socket. Provides ability to [connect], [read] and [write].
 */
interface AsyncSocket : AReadWriteSocket, ABoundSocket, AConnectedSocket

interface AsyncUnboundServerSocket : ASocket, ABoundableSocket<AsyncServerSocket> {
    /**
     * Bind server socket to the specified [localAddress].
     * Will automatically choose some random local address if [localAddress] is null.
     * Could fail if [localAddress] is already in use or if there are no free local ports available.
     */
    override fun bind(localAddress: SocketAddress?): AsyncServerSocket
}

/**
 * Represents a server TCP socket that could be bound via [bind] and used to [accept] connections.
 */
interface AsyncServerSocket : ASocket, ABoundSocket, AsyncAcceptable<AsyncSocket>

interface AsyncFreeDatagramSocket  : ASocket, ABoundableSocket<AsyncBoundDatagramSocket>, AConnectableSocket<AsyncConnectedDatagramSocket>, DatagramReadWriteChannel

interface AsyncBoundDatagramSocket : ASocket, ABoundSocket, AConnectableSocket<AsyncConnectedDatagramSocket>, ReadChannel, DatagramWriteChannel

interface AsyncConnectedDatagramSocket : AReadWriteSocket, ABoundSocket, AConnectedSocket

