package kotlinx.sockets

import java.net.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

internal class AsyncServerSocketImpl(override val channel: ServerSocketChannel, val selector: SelectorManager) : AsyncServerSocket, SelectableBase() {
    init {
        require(!channel.isBlocking)
    }

    private val acceptContinuation = AtomicReference<Continuation<AsyncSocket?>?>()

    @Volatile
    override var interestedOps: Int = 0

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override fun <T> setOption(name: SocketOption<T>, value: T) {
        channel.setOption(name, value)
    }

    override fun bind(localAddress: SocketAddress?) {
        channel.bind(localAddress)
    }

    override fun onSelected(key: SelectionKey) {
        if (onSelectedGeneric(key, SelectionKey.OP_ACCEPT, acceptContinuation) { it.resume(null) }) {
            pushInterestDirect(key)
        }
    }

    override fun onSelectionFailed(t: Throwable) {
        interestedOps = 0
        acceptContinuation.invokeIfPresent { resumeWithException(t) }
    }

    suspend override fun accept(): AsyncSocket {
        while (true) {
            return suspendCoroutineOrReturn<AsyncSocket?> { c ->
                val nioChannel = channel.accept()

                if (nioChannel != null) {
                    wantAccept(false)
                    nioChannel.configureBlocking(false)
                    AsyncSocketImpl(nioChannel, selector)
                } else {
                    acceptContinuation.setHandler("accept", c)
                    wantAccept(true)
                    pushInterest(selector)

                    COROUTINE_SUSPENDED
                }
            } ?: continue
        }
    }

    private fun wantAccept(flag: Boolean) {
        interestOp(SelectionKey.OP_ACCEPT, flag)
    }

    override fun close() {
        interestedOps = 0
        channel.close()
    }
}