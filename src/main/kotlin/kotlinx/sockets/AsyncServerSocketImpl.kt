package kotlinx.sockets

import java.net.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

internal class AsyncServerSocketImpl(override val channel: ServerSocketChannel, val selector: SelectorManager) : AsyncServerSocket, AsyncSelectable {
    init {
        require(!channel.isBlocking)
    }

    private val acceptContinuation = AtomicReference<Continuation<AsyncSocket?>?>()

    override var interestedOps: Int = 0
        private set

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override fun <T> setOption(name: SocketOption<T>, value: T) {
        channel.setOption(name, value)
    }

    override fun bind(localAddress: SocketAddress) {
        channel.bind(localAddress)
    }

    suspend override fun onSelected(key: SelectionKey) {
        if (key.isAcceptable) {
            acceptContinuation.take().resume(null)
        } else {
            interest(false)
        }
    }

    suspend override fun accept(): AsyncSocket {
        while (true) {
            return suspendCoroutineOrReturn<AsyncSocket?> { c ->
                val nioChannel = channel.accept()

                if (nioChannel != null) {
                    nioChannel.configureBlocking(false)
                    AsyncSocketImpl(nioChannel, selector)
                } else {
                    acceptContinuation.setHandler("accept", c)
                    interest(true)

                    COROUTINE_SUSPENDED
                }
            } ?: continue
        }
    }

    fun interest(flag: Boolean) {
        interestFlag(selector, SelectionKey.OP_ACCEPT, flag, { interestedOps = it })
    }

    override fun close() {
        channel.close()
    }
}