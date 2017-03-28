package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

internal class AsyncServerSocketImpl(override val channel: ServerSocketChannel, val selector: SelectorManager) : AsyncServerSocket, AsyncUnboundServerSocket, SelectableBase() {
    init {
        require(!channel.isBlocking)
    }

    private val acceptContinuation = AtomicReference<Continuation<Nothing?>?>()

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override fun <T> setOption(option: SocketOption<T>, value: T) {
        channel.setOption(option, value)
    }

    override fun <T> getOption(option: SocketOption<T>): T {
        return channel.getOption(option)
    }

    override val supportedOptions: Set<SocketOption<*>>
        get() = channel.supportedOptions()

    override fun bind(localAddress: SocketAddress?): AsyncServerSocket {
        channel.bind(localAddress)
        return this
    }

    override fun onSelected(key: SelectionKey) {
        if (onSelectedGeneric(key, SelectionKey.OP_ACCEPT, acceptContinuation) { it.resume(null) }) {
            pushInterestDirect(key)
        }
    }

    override fun onSelectionFailed(t: Throwable) {
        interestedOps = 0
        acceptContinuation.invokeIfPresent { resumeWithException(t) }
        interestedOps = 0
    }

    suspend override fun accept(): AsyncSocket {
        while (true) {
            channel.accept()?.let { return accepted(it) }

            suspendCancellableCoroutine<Nothing?> { c ->
                acceptContinuation.setHandler("accept", c)
                acceptContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                wantAccept(true)
                pushInterest(selector)
            }
        }
    }

    private fun accepted(nioChannel: SocketChannel): AsyncSocket {
        nioChannel.configureBlocking(false)
        return AsyncSocketImpl(nioChannel, selector)
    }

    private fun wantAccept(flag: Boolean) {
        interestOp(SelectionKey.OP_ACCEPT, flag)
    }

    override fun close() {
        interestedOps = 0
        try {
            channel.close()
        } finally {
            try {
                onSelectionFailed(ClosedChannelException())
            } catch (expected: CancelledKeyException) {
            } finally {
                selector.ensureUnregistered()
            }
        }
    }
}