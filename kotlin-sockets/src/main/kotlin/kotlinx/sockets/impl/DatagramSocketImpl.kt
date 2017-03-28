package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

internal class DatagramSocketImpl(override val channel: DatagramChannel, val selector: SelectorManager) : AsyncFreeDatagramSocket, AsyncBoundDatagramSocket, AsyncConnectedDatagramSocket, SelectableBase() {

    private val receiveOrReadContinuation = AtomicReference<Continuation<Unit>?>(null)
    private val writeOrSendContinuation = AtomicReference<Continuation<Unit>?>(null)

    override val localAddress: SocketAddress
        get() = channel.localAddress ?: throw IllegalStateException("Channel is not yet bound")

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress ?: throw IllegalStateException("Channel is not yet connected")

    override fun shutdownOutput() {
    }

    suspend override fun receive(dst: ByteBuffer): SocketAddress {
        while (true) {
            channel.receive(dst)?.let { interestOp(SelectionKey.OP_READ, false); return it }

            suspendCancellableCoroutine<Unit> { c ->
                receiveOrReadContinuation.setHandler("receive", c)
                receiveOrReadContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                interestOp(SelectionKey.OP_READ, true)
                pushInterest(selector)
            }
        }
    }

    suspend override fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = channel.read(dst)

            if (rc > 0) {
                interestOp(SelectionKey.OP_READ, false)
                return rc
            }

            suspendCancellableCoroutine<Unit> { c ->
                receiveOrReadContinuation.setHandler("read", c)
                receiveOrReadContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                interestOp(SelectionKey.OP_READ, true)
                pushInterest(selector)
            }
        }
    }

    suspend override fun write(src: ByteBuffer, target: SocketAddress) {
        while (true) {
            val rc = channel.send(src, target)
            if (rc > 0) {
                interestOp(SelectionKey.OP_WRITE, false)
            }

            suspendCancellableCoroutine<Unit> { c ->
                writeOrSendContinuation.setHandler("write", c)
                writeOrSendContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                interestOp(SelectionKey.OP_WRITE, true)
                pushInterest(selector)
            }
        }
    }

    suspend override fun write(src: ByteBuffer) {
        while (true) {
            val rc = channel.write(src)
            if (rc > 0) {
                interestOp(SelectionKey.OP_WRITE, false)
            }

            suspendCancellableCoroutine<Unit> { c ->
                writeOrSendContinuation.setHandler("write", c)
                writeOrSendContinuation.setNullOnCancel(c)
                c.disposeOnCancel(this)

                interestOp(SelectionKey.OP_WRITE, true)
                pushInterest(selector)
            }
        }
    }

    override fun <T> setOption(option: SocketOption<T>, value: T) {
        channel.setOption(option, value)
    }

    override fun <T> getOption(option: SocketOption<T>): T {
        return channel.getOption(option)
    }

    override val supportedOptions: Set<SocketOption<*>>
        get() = channel.supportedOptions()

    override fun close() {
        channel.close()
    }

    suspend override fun connect(target: SocketAddress): AsyncConnectedDatagramSocket {
        channel.connect(target)
        return this
    }

    override fun bind(localAddress: SocketAddress?): AsyncBoundDatagramSocket {
        channel.bind(localAddress)
        return this
    }

    override fun onSelected(key: SelectionKey) {
        val changed = onSelectedGeneric(key, SelectionKey.OP_READ, receiveOrReadContinuation, { it.resume(Unit) }) or
            onSelectedGeneric(key, SelectionKey.OP_WRITE, writeOrSendContinuation, { it.resume(Unit) })

        if (changed) {
            pushInterestDirect(key)
        }
    }

    override fun onSelectionFailed(t: Throwable) {
        interestedOps = 0
        receiveOrReadContinuation.invokeIfPresent { resumeWithException(t) }
        writeOrSendContinuation.invokeIfPresent { resumeWithException(t) }
        interestedOps = 0
    }
}