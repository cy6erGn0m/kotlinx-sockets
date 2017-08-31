package kotlinx.sockets.impl

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.atomic.*

internal abstract class NIOSocketImpl<out S>(override val channel: S, val selector: SelectorManager, val pool: ObjectPool<ByteBuffer>) : ReadWriteSocket, Selectable
        where S : java.nio.channels.ByteChannel, S : java.nio.channels.SelectableChannel {

    private val closeFlag = AtomicBoolean()
    private val readerJob = AtomicReference<ReaderJob?>()
    private val writerJob = AtomicReference<WriterJob?>()

    override val closed = CompletableDeferred<Unit>()

    override final fun attachForReading(channel: ByteChannel): WriterJob {
        return attachFor("reading", channel, writerJob) {
            attachForReadingImpl(channel, this.channel, this, selector, pool)
        }
    }

    override final fun attachForWriting(channel: ByteChannel): ReaderJob {
        return attachFor("writing", channel, readerJob) {
            attachForWritingImpl(channel, this.channel, this, selector, pool)
        }
    }

    override fun close() {
        if (closeFlag.compareAndSet(false, true)) {
            readerJob.get()?.channel?.close()
            writerJob.get()?.cancel()
            checkChannels()
        }
    }

    private fun <J : Job> attachFor(name: String, channel: ByteChannel, ref: AtomicReference<J?>, producer: () -> J): J {
        if (closeFlag.get()) {
            val e = ClosedChannelException()
            channel.close(e)
            throw e
        }

        val j = producer()

        if (!ref.compareAndSet(null, j)) {
            val e = IllegalStateException("$name channel has been already set")
            j.cancel(e)
            throw e
        }
        if (closeFlag.get()) {
            val e = ClosedChannelException()
            j.cancel(e)
            channel.close(e)
            throw e
        }

        j.invokeOnCompletion {
            checkChannels()
        }

        return j
    }

    private fun actualClose(): Throwable? {
        return try {
            channel.close()
            null
        } catch (t: Throwable) {
            t
        } finally {
            selector.notifyClosed(this)
        }
    }

    private fun checkChannels() {
        if (closeFlag.get() && readerJob.completedOrNotStarted && writerJob.completedOrNotStarted) {
            val e1 = readerJob.exception
            val e2 = writerJob.exception
            val e3 = actualClose()

            val combined = combine(combine(e1, e2), e3)

            if (combined == null) closed.complete(Unit) else closed.completeExceptionally(combined)
        }
    }

    private fun combine(e1: Throwable?, e2: Throwable?): Throwable? = when {
        e1 == null -> e2
        e2 == null -> e1
        e1 === e2 -> e1
        else -> {
            e1.addSuppressed(e2)
            e1
        }
    }

    private val AtomicReference<out Job?>.completedOrNotStarted: Boolean
        get() = get().let { it == null || it.isCompleted }

    private val AtomicReference<out Job?>.exception: Throwable?
        get() = get()?.takeUnless { it.isActive || it.isCancelled }?.getCompletionException()?.takeUnless { it is CancellationException }
}