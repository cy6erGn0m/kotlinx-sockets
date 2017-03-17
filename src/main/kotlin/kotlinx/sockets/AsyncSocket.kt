package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.io.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

open class AsyncSocket<out S : SocketChannel>(override final val channel: S, val selector: SelectorManager) : AsyncSelectable, AutoCloseable, Closeable {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    @Volatile
    final override var interestedOps: Int = 0
        private set

    private val connectContinuation = AtomicReference<Continuation<Boolean>?>()
    private val readContinuation = AtomicReference<Continuation<Unit>?>()
    private val writeContinuation = AtomicReference<Continuation<Unit>?>()

    override suspend fun onSelected(key: SelectionKey) {
        if (key.isConnectable) {
            connectContinuation.getAndSet(null)?.resume(false)
        } else {
            wantConnect(false)
        }

        if (key.isReadable) {
            readContinuation.getAndSet(null)?.resume(Unit)
        } else {
            wantMoreBytesRead(false)
        }

        if (key.isWritable) {
            writeContinuation.getAndSet(null)?.resume(Unit)
        } else {
            wantMoreSpaceForWrite(false)
        }
    }

    suspend fun connect(p: SocketAddress) {
        var connected = suspendCoroutineOrReturn<Boolean> { c ->
            if (channel.connect(p)) {
                true
            } else {
                if (!connectContinuation.compareAndSet(null, c)) throw IllegalStateException()
                wantConnect(true)

                COROUTINE_SUSPENDED
            }
        }

        while (!connected) {
            connected = suspendCoroutineOrReturn<Boolean> { c ->
                if (channel.finishConnect()) {
                    true
                } else {
                    if (!connectContinuation.compareAndSet(null, c)) throw IllegalStateException()
                    wantConnect(true)

                    COROUTINE_SUSPENDED
                }
            }
        }
    }

    suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = suspendCoroutineOrReturn<Any> {
                val rc = channel.read(dst)
                if (rc > 0) rc
                else {
                    if (!readContinuation.compareAndSet(null, it)) throw IllegalStateException()
                    wantMoreBytesRead()

                    COROUTINE_SUSPENDED
                }
            }

            if (rc is Int) return rc
        }
    }

    suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            suspendCoroutineOrReturn<Unit> { c ->
                val rc = channel.write(src)

                if (rc == 0) {
                    if (!writeContinuation.compareAndSet(null, c)) throw IllegalStateException()
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
        interestFlag(SelectionKey.OP_CONNECT, state)
    }

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestFlag(SelectionKey.OP_READ, state)
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestFlag(SelectionKey.OP_WRITE, state)
    }

    private fun interestFlag(flag: Int, state: Boolean) {
        val newOps = if (state) interestedOps or flag else interestedOps and flag.inv()
        if (interestedOps != newOps) {
            launch(selector.dispatcher) {
                interestedOps = newOps
                selector.registerSafe(this@AsyncSocket)
            }
        }
    }
}
