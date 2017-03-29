package kotlinx.sockets.impl

import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import kotlin.coroutines.experimental.*

internal class AsyncSocketImpl<out S : java.nio.channels.SocketChannel>(override val channel: S, val selector: kotlinx.sockets.selector.SelectorManager) : kotlinx.sockets.selector.SelectableBase(), kotlinx.sockets.AsyncSocket {
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

    override fun onSelected(key: java.nio.channels.SelectionKey) {
        val changed = onSelectedGeneric(key, java.nio.channels.SelectionKey.OP_CONNECT, connectContinuation) { it.resume(false) } or
                onSelectedGeneric(key, java.nio.channels.SelectionKey.OP_READ, readContinuation) { it.resume(Unit) } or
                onSelectedGeneric(key, java.nio.channels.SelectionKey.OP_WRITE, writeContinuation) { it.resume(Unit) }

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

    internal suspend fun connect(target: java.net.SocketAddress): kotlinx.sockets.AsyncSocket {
        var connected = channel.connect(target) || kotlinx.coroutines.experimental.suspendCancellableCoroutine<Boolean> { c ->
            connectContinuation.setHandler("connect", c)
            connectContinuation.setNullOnCancel(c)
            c.disposeOnCancel(this)

            wantConnect(true)
            pushInterest(selector)
        }

        while (!connected) {
            connected = channel.finishConnect() || kotlinx.coroutines.experimental.suspendCancellableCoroutine<Boolean> { c ->
                connectContinuation.setHandler("finishConnect", c)
                connectContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                wantConnect(true)
                pushInterest(selector)
            }
        }

        wantConnect(false)

        return this
    }

    override suspend fun read(dst: java.nio.ByteBuffer): Int {
        while (true) {
            val rc = channel.read(dst)

            if (rc == 0 && dst.hasRemaining()) {
                kotlinx.coroutines.experimental.suspendCancellableCoroutine<Unit> { c ->
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

    override suspend fun write(src: java.nio.ByteBuffer) {
        while (src.hasRemaining()) {
            val rc = channel.write(src)

            if (rc == 0 && src.hasRemaining()) {
                kotlinx.coroutines.experimental.suspendCancellableCoroutine<Unit> { c ->
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
                onSelectionFailed(java.nio.channels.ClosedChannelException())
            } catch (expected: java.nio.channels.CancelledKeyException) {
            } finally {
                interestedOps = 0
                selector.ensureUnregistered()
            }
        }
    }

    private fun wantConnect(state: Boolean = true) {
        interestOp(java.nio.channels.SelectionKey.OP_CONNECT, state)
    }

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestOp(java.nio.channels.SelectionKey.OP_READ, state)
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestOp(java.nio.channels.SelectionKey.OP_WRITE, state)
    }
}
