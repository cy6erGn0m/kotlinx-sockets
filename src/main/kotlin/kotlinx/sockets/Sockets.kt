package kotlinx.sockets

import java.io.*
import java.net.*
import java.net.Socket
import java.nio.*

interface AsyncLocalPeer : Closeable {
    val localAddress: SocketAddress
}

interface AsyncConnection : Closeable {
    val remoteAddress: SocketAddress
}

interface ConfigurableSocket {
    fun <T> setOption(name: SocketOption<T>, value: T)
}

interface AsyncSocket : AsyncLocalPeer, AsyncConnection, ConfigurableSocket {
    suspend fun connect(address: SocketAddress)
    suspend fun read(dst: ByteBuffer): Int
    suspend fun write(src: ByteBuffer)
}

interface AsyncServerSocket : AsyncLocalPeer, ConfigurableSocket {
    suspend fun bind(localAddress: SocketAddress)
    suspend fun accept(): Socket
}
