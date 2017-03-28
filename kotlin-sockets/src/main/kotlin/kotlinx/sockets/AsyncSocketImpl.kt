package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

internal class AsyncSocketImpl<out S : SocketChannel>(override val channel: S, val selector: SelectorManager) : SelectableBase(), AsyncSocket {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    @Volatile
    override var interestedOps: Int = 0

    private val connectContinuation = AtomicReference<Continuation<Boolean>?>()
    private val readContinuation = AtomicReference<Continuation<Unit>?>()
    private val writeContinuation = AtomicReference<Continuation<Unit>?>()

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress

    override fun <T> setOption(option: SocketOption<T>, value: T) {
        channel.setOption(option, value)
    }

    override fun onSelected(key: SelectionKey) {
        val changed = onSelectedGeneric(key, SelectionKey.OP_CONNECT, connectContinuation) { it.resume(false) } or
                onSelectedGeneric(key, SelectionKey.OP_READ, readContinuation) { it.resume(Unit) } or
                onSelectedGeneric(key, SelectionKey.OP_WRITE, writeContinuation) { it.resume(Unit) }

        if (changed) {
            pushInterestDirect(key)
        }
    }

    override fun onSelectionFailed(t: Throwable) {
        interestedOps = 0
        connectContinuation.invokeIfPresent { resumeWithException(t) }
        readContinuation.invokeIfPresent { resumeWithException(t) }
        writeContinuation.invokeIfPresent { resumeWithException(t) }
        interestedOps = 0
    }

    override suspend fun connect(address: SocketAddress) {
        var connected = channel.connect(address) || suspendCancellableCoroutine<Boolean> { c ->
            connectContinuation.setHandler("connect", c)
            connectContinuation.setNullOnCancel(c)
            c.disposeOnCancel(this)

            wantConnect(true)
            pushInterest(selector)
        }

        while (!connected) {
            connected = channel.finishConnect() || suspendCancellableCoroutine<Boolean> { c ->
                connectContinuation.setHandler("connect", c)
                connectContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                wantConnect(true)
                pushInterest(selector)
            }
        }

        wantConnect(false)
    }

    override suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = channel.read(dst)

            if (rc == 0 && dst.hasRemaining()) {
                suspendCancellableCoroutine<Unit> { c ->
                    readContinuation.setHandler("read", c)
                    readContinuation.setNullOnCancel(c)
                    c.disposeOnCancel(this)

                    wantMoreBytesRead()
                    pushInterest(selector)
                }
            } else {
                wantMoreBytesRead(false)
                return rc
            }
        }
    }

    override suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            val rc = channel.write(src)

            if (rc == 0 && src.hasRemaining()) {
                suspendCancellableCoroutine<Unit> { c ->
                    writeContinuation.setHandler("write", c)
                    writeContinuation.setNullOnCancel(c)
                    c.disposeOnCancel(this)

                    wantMoreSpaceForWrite()
                    pushInterest(selector)
                }
            } else {
                wantMoreSpaceForWrite(false)
                return
            }
        }
    }

    override fun shutdownOutput() {
        channel.shutdownOutput()
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            try {
                onSelectionFailed(ClosedChannelException())
            } catch (expected: CancelledKeyException) {
            } finally {
                interestedOps = 0
                selector.ensureUnregistered()
            }
        }
    }

    private fun wantConnect(state: Boolean = true) {
        interestOp(SelectionKey.OP_CONNECT, state)
    }

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestOp(SelectionKey.OP_READ, state)
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestOp(SelectionKey.OP_WRITE, state)
    }
}
