package kotlinx.sockets

import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

internal class AsyncSocketImpl<out S : SocketChannel>(override val channel: S, val selector: SelectorManager) : AsyncSelectable, AsyncSocket {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    @Volatile
    override var interestedOps: Int = 0
        private set

    private val connectContinuation = AtomicReference<Continuation<Boolean>?>()
    private val readContinuation = AtomicReference<Continuation<Unit>?>()
    private val writeContinuation = AtomicReference<Continuation<Unit>?>()

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress

    override fun <T> setOption(name: SocketOption<T>, value: T) {
        channel.setOption(name, value)
    }

    override suspend fun onSelected(key: SelectionKey) {
        if (key.isConnectable) {
            connectContinuation.take().resume(false)
        } else {
            wantConnect(false)
        }

        if (key.isReadable) {
            readContinuation.take().resume(Unit)
        } else {
            wantMoreBytesRead(false)
        }

        if (key.isWritable) {
            writeContinuation.take().resume(Unit)
        } else {
            wantMoreSpaceForWrite(false)
        }
    }

    override suspend fun connect(address: SocketAddress) {
        var connected = suspendCoroutineOrReturn<Boolean> { c ->
            if (channel.connect(address)) {
                true
            } else {
                connectContinuation.setHandler("connect", c)
                wantConnect(true)

                COROUTINE_SUSPENDED
            }
        }

        while (!connected) {
            connected = suspendCoroutineOrReturn<Boolean> { c ->
                if (channel.finishConnect()) {
                    true
                } else {
                    connectContinuation.setHandler("connect", c)
                    wantConnect(true)

                    COROUTINE_SUSPENDED
                }
            }
        }
    }

    override suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = suspendCoroutineOrReturn<Any> { c ->
                val rc = channel.read(dst)
                if (rc > 0) rc
                else {
                    readContinuation.setHandler("read", c)
                    wantMoreBytesRead()

                    COROUTINE_SUSPENDED
                }
            }

            if (rc is Int) return rc
        }
    }

    override suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            suspendCoroutineOrReturn<Unit> { c ->
                val rc = channel.write(src)

                if (rc == 0) {
                    writeContinuation.setHandler("write", c)
                    wantMoreSpaceForWrite()

                    COROUTINE_SUSPENDED
                } else {
                    Unit
                }
            }
        }
    }

    override fun close() {
        channel.close()
    }

    private fun wantConnect(state: Boolean = true) {
        interestFlag(selector, SelectionKey.OP_CONNECT, state, { interestedOps = it })
    }

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestFlag(selector, SelectionKey.OP_READ, state, { interestedOps = it })
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestFlag(selector, SelectionKey.OP_WRITE, state, { interestedOps = it })
    }
}
