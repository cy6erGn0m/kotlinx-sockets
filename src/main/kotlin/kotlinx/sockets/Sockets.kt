package kotlinx.sockets

import java.io.*
import java.net.*

interface AsyncLocalPeer : Closeable {
    val localAddress: SocketAddress
}

interface AsyncConnection : Closeable {
    val remoteAddress: SocketAddress
}

interface ConfigurableSocket {
    fun <T> setOption(name: SocketOption<T>, value: T)
}

interface AsyncSocket : AsyncLocalPeer, AsyncConnection, ReadChannel, WriteChannel, ConfigurableSocket {
    suspend fun connect(address: SocketAddress)
}

interface AsyncServerSocket : AsyncLocalPeer, ConfigurableSocket, SocketSource {
    fun bind(localAddress: SocketAddress)
}
