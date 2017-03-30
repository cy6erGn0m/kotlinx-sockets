package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.nio.*
import java.nio.channels.*
import kotlin.coroutines.experimental.*

internal class AsyncSocketImpl<out S : SocketChannel>(override val channel: S, val selector: SelectorManager) : SelectableBase(), AsyncSocket {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    private val connectContinuation = java.util.concurrent.atomic.AtomicReference<Continuation<Boolean>?>()
    private val readContinuation = java.util.concurrent.atomic.AtomicReference<Continuation<Unit>?>()
    private val writeContinuation = java.util.concurrent.atomic.AtomicReference<Continuation<Unit>?>()

    override val localAddress: java.net.SocketAddress
        get() = channel.localAddress

    override val remoteAddress: java.net.SocketAddress
        get() = channel.remoteAddress

    override fun onSelected(key: SelectionKey) {
        val changed = onSelectedGeneric(key, SelectionKey.OP_CONNECT, connectContinuation) { it.resume(false) } or
                onSelectedGeneric(key, SelectionKey.OP_READ, readContinuation) { it.resume(Unit) } or
                onSelectedGeneric(key, SelectionKey.OP_WRITE, writeContinuation) { it.resume(Unit) }

        if (changed) {
            key.interestOps(interestedOps)
        }
    }

    override fun onSelectionFailed(t: Throwable) {
        interestedOps = 0
        connectContinuation.invokeIfPresent { resumeWithException(t) }
        readContinuation.invokeIfPresent { resumeWithException(t) }
        writeContinuation.invokeIfPresent { resumeWithException(t) }
        interestedOps = 0
    }

    internal suspend fun connect(target: java.net.SocketAddress): kotlinx.sockets.AsyncSocket {
        var connected = channel.connect(target) || suspendCancellableCoroutine<Boolean> { c ->
            connectContinuation.setHandler("connect", c)
            connectContinuation.setNullOnCancel(c)
            c.disposeOnCancel(this)

            wantConnect(true)
            selector.notifyInterest(this)
        }

        while (!connected) {
            connected = channel.finishConnect() || suspendCancellableCoroutine<Boolean> { c ->
                connectContinuation.setHandler("finishConnect", c)
                connectContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                wantConnect(true)
                selector.notifyInterest(this)
            }
        }

        wantConnect(false)

        return this
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
                    selector.notifyInterest(this)
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
                    selector.notifyInterest(this)
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
                selector.notifyClosed(this)
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
