package kotlinx.sockets

import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

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
        var connected = suspendCoroutineOrReturn<Boolean> { c ->
            if (channel.connect(address)) {
                true
            } else {
                connectContinuation.setHandler("connect", c)
                wantConnect(true)
                pushInterest(selector)

                COROUTINE_SUSPENDED
            }
        }

        while (!connected) {
            connected = suspendCoroutineOrReturn<Boolean> { c ->
                if (channel.finishConnect()) {
                    wantConnect(false)
                    true
                } else {
                    connectContinuation.setHandler("connect", c)
                    wantConnect(true)
                    pushInterest(selector)

                    COROUTINE_SUSPENDED
                }
            }
        }
    }

    override suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = suspendCoroutineOrReturn<Any> { c ->
                val rc = channel.read(dst)

                if (rc == 0 && dst.hasRemaining()) {
                    readContinuation.setHandler("read", c)
                    wantMoreBytesRead()
                    pushInterest(selector)

                    COROUTINE_SUSPENDED
                } else {
                    wantMoreBytesRead(false)
                    rc
                }
            }

            if (rc is Int) return rc
        }
    }

    override suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            suspendCoroutineOrReturn<Unit> { c ->
                val rc = channel.write(src)

                if (rc == 0 && src.hasRemaining()) {
                    writeContinuation.setHandler("write", c)
                    wantMoreSpaceForWrite()
                    pushInterest(selector)

                    COROUTINE_SUSPENDED
                } else {
                    wantMoreSpaceForWrite(false)

                    Unit
                }
            }
        }
    }

    override fun close() {
        channel.close()

        val closed = ClosedChannelException()
        onSelectionFailed(closed)
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
