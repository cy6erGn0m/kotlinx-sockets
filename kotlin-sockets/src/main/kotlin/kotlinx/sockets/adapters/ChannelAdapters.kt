package kotlinx.sockets.adapters

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.channels.*
import kotlinx.sockets.channels.impl.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import java.nio.charset.*
import java.util.concurrent.atomic.*

/**
 * Opens a receive channel of specified [capacity] and starts a producer job to populate it.
 */
fun ReadChannel.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<ByteBuffer> {
    return openReceiveChannel(pool, capacity) { it }
}

/**
 * Opens a receive channel of specified [capacity] and starts a producer job to populate it using mapper
 * function [transform].
 */
fun <C : ReadChannel, R> C.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(ByteBuffer) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveLoop(this@openReceiveChannel, channel, pool, transform)
    }
}

/**
 * Creates a job to receive that takes byte buffers from a [pool], reads bytes to a buffer and sends the buffer to [channel]
 * @return a job that is not yet started
 */
fun ReadChannel.receiveTo(channel: SendChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return receiveTo(channel, pool, { it })
}

/**
 * Creates a job to receive that takes byte buffers from a [pool], reads bytes to a buffer,
 * transforms it by [transform] mapper function and sends result [R] to [channel].
 * @return a job that is not yet started
 */
fun <C : ReadChannel, R> C.receiveTo(channel: SendChannel<R>, pool: Channel<ByteBuffer>, transform: C.(ByteBuffer) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        receiveLoop(this@receiveTo, channel, pool, transform)
    }
}

private suspend fun <C : ReadChannel, R> receiveLoop(source: C, channel: SendChannel<R>, pool: Channel<ByteBuffer>, transform: C.(ByteBuffer) -> R) {
    while (true) {
        val bb = pool.receive()
        bb.clear()

        val rc = try {
            source.read(bb)
        } catch (t: Throwable) {
            pool.offer(bb)
            throw t
        }

        if (rc == -1) {
            pool.offer(bb)
            break
        } else {
            bb.flip()
            channel.send(transform(source, bb))
        }
    }
}

/**
 * Creates a job to receive lines decoded by [charset] decoder to the specified [destination] channel.
 * @return a job that is not yet started
 */
fun ReadChannel.receiveLinesTo(destination: SendChannel<String>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return receiveLinesTo(destination, charset, pool) { it }
}

/**
 * Creates a job to receive lines decoded by [charset] decoder to the specified [destination] channel passing through
 * the given [transform] function.
 * @return a job that is not yet started
 */
fun <C : ReadChannel, R> C.receiveLinesTo(destination: SendChannel<R>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(String) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        receiveLinesLoop(this@receiveLinesTo, destination, charset, pool, transform)
    }
}

/**
 * Opens a channel of lines decoded by [charset] decoder.
 * @return a running [ProducerJob]
 */
fun ReadChannel.openLinesReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    return openLinesReceiveChannel(charset, pool, capacity) { it }
}

/**
 * Opens a channel of lines decoded by [charset] decoder and mapped by [transform] function.
 * @return a running [ProducerJob]
 */
fun <C : ReadChannel, R> C.openLinesReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(String) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveLinesLoop(this@openLinesReceiveChannel, this, charset, pool, transform)
    }
}

/**
 * Opens a channel of text parts decoded by [charset] decoder.
 * @return a running [ProducerJob]
 */
fun ReadChannel.openTextReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    return openTextReceiveChannel(charset, pool, capacity) { it }
}

/**
 * Opens a channel of text parts decoded by [charset] decoder and mapped by [transform] function.
 * @return a running [ProducerJob]
 */
fun <C : ReadChannel, R> C.openTextReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(String) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveTextLoop(this@openTextReceiveChannel, this, charset, pool, transform)
    }
}

/**
 * Creates a job to receive text decoded by [charset] decoder to the specified [destination] channel.
 * @return a job that is not yet started
 */
fun ReadChannel.receiveTextTo(destination: SendChannel<String>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return receiveTextTo(destination, charset, pool) { it }
}

/**
 * Creates a job to receive text decoded by [charset] decoder to the specified [destination] channel passing through
 * the given [transform] function.
 *
 * @return a job that is not yet started
 */
fun <C : ReadChannel, R> C.receiveTextTo(destination: SendChannel<R>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(String) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        receiveTextLoop(this@receiveTextTo, destination, charset, pool, transform)
    }
}

private suspend fun <C : ReadChannel, R> receiveTextLoop(source: C, destination: SendChannel<R>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(String) -> R) {
    val buffer = pool.receive()
    val chs = source.asCharChannel(charset, buffer)

    val blockBuffer = pool.receive().apply { clear() }
    val charBuffer = blockBuffer.asCharBuffer()

    try {
        while (true) {
            charBuffer.clear()
            if (chs.read(charBuffer) == -1) break
            charBuffer.flip()
            val text = charBuffer.toString()

            destination.send(transform(source, text))
        }
    } finally {
        pool.offer(buffer)
        pool.offer(blockBuffer)
    }
}

private suspend fun <C : ReadChannel, R> receiveLinesLoop(source: C, destination: SendChannel<R>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(String) -> R) {
    val buffer = pool.receive()
    val charBuffer = pool.receive()
    val chs = source.asCharChannel(charset, buffer).buffered { charBuffer.asCharBuffer() }

    try {
        while (true) {
            val line = chs.readLine() ?: break
            destination.send(transform(source, line))
        }
    } finally {
        pool.offer(buffer)
        pool.offer(charBuffer)
    }
}

/**
 * Opens a send channel and starts an [ActorJob] that writes buffers from the channel to the socket
 * and returns them to [pool].
 *
 * @return a running actor job with channel
 */
fun WriteChannel.openSendChannel(pool: Channel<ByteBuffer>, capacity: Int = 2): ActorJob<ByteBuffer> {
    return openSendChannel(pool, capacity) { it }
}

/**
 * Opens a send channel and starts an [ActorJob] that transforms messages from the channel by [transform] function
 * and writes resulting buffers to the socket and returns them to [pool].
 *
 * @return a running actor job with channel
 */
fun <C : WriteChannel, T> C.openSendChannel(pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(T) -> ByteBuffer): ActorJob<T> {
    return actor(ioCoroutineDispatcher, capacity) {
        writeLoop(this@openSendChannel, this, pool, transform)
    }
}

/**
 * Creates a job to take byte buffers from [source] channel and write to the socket.
 * @return a job that is not yet started
 */
fun WriteChannel.sendFrom(source: ReceiveChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return sendFrom(source, pool, { it })
}

/**
 * Creates a job to [transform] messages of type [T] from [source] channel to bytes and write them to the socket.
 * @return a job that is not yet started
 */
fun <C : WriteChannel, T> C.sendFrom(source: ReceiveChannel<T>, pool: Channel<ByteBuffer>, transform: C.(T) -> ByteBuffer): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        writeLoop(this@sendFrom, source, pool, transform)
    }
}

/**
 * Creates a job to take text blocks from [channel], encode by [charset] encoder and write to the socket.
 * @return a job that is not yet started
 */
fun WriteChannel.sendTextFrom(channel: ReceiveChannel<CharSequence>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return sendTextFrom(channel, charset, pool, { it })
}

/**
 * Creates a job to transform messages of type [T] from [source] channel, encode by [charset] encoder and write to the socket.
 * @return a job that is not yet started
 */
fun <C : WriteChannel, T> C.sendTextFrom(source: ReceiveChannel<T>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(T) -> CharSequence): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        encodeAndWriteLoop(this@sendTextFrom, source, charset, pool, transform)
    }
}

private suspend fun <C : WriteChannel, T> writeLoop(destination: C, source: ReceiveChannel<T>, pool: Channel<ByteBuffer>, transform: C.(T) -> ByteBuffer) {
    var pushBack: ByteBuffer? = null

    while (true) {
        val first = pushBack ?: source.receiveOrNull()?.let { transform(destination, it) } ?: break
        var out: ByteBuffer? = null

        if (first.remaining() < 8192) {
            while (out == null || out.hasRemaining()) {
                val more = source.poll()?.let { transform(destination, it) } ?: break

                if (out == null) {
                    out = pool.receive()
                    out.put(first)
                    pool.offer(first)
                }

                if (more.remaining() <= out.remaining()) {
                    out.put(more)
                    pool.offer(more)
                } else {
                    pushBack = more
                    break
                }
            }
        }

        if (out != null) {
            out.flip()
            writeImpl(destination, pool, out)
            pool.offer(out)
        } else {
            writeImpl(destination, pool, first)
            pool.offer(first)
        }
    }

    if (pushBack != null) {
        writeImpl(destination, pool, pushBack)
        pool.offer(pushBack)
    }
}

/**
 * Opens a text send channel and starts an [ActorJob] that writes text blocks encoded by [charset] encoder to the socket.
 *
 * @return a running actor job with channel
 */
fun WriteChannel.openTextSendChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ActorJob<CharSequence> {
    return openTextSendChannel(charset, pool, capacity) { it }
}

/**
 * Opens a text send channel and starts an [ActorJob] that transforms messages of type [T] to text blocks and
 * writes the blocks encoded by [charset] encoder to the socket.
 *
 * @return a running actor job with channel
 */
fun <C : WriteChannel, T> C.openTextSendChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(T) -> CharSequence): ActorJob<T> {
    return actor(ioCoroutineDispatcher, capacity) {
        encodeAndWriteLoop(this@openTextSendChannel, this, charset, pool, transform)
    }
}

private suspend fun <C : WriteChannel, T> encodeAndWriteLoop(destination: C, source: ReceiveChannel<T>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(T) -> CharSequence) {
    val encoder = charset.newEncoder()!!
    var pushBack: CharBuffer? = null

    while (true) {
        var cb = pushBack ?: source.receiveOrNull()?.let { CharBuffer.wrap(transform(destination, it)) } ?: break
        val buffer = pool.receive()
        buffer.clear()

        val r = encoder.encode(cb, buffer, true)
        if (r.isUnmappable) r.throwException()

        while (!cb.hasRemaining() && buffer.hasRemaining()) {
            cb = source.poll()?.let { CharBuffer.wrap(transform(destination, it)) } ?: break

            encoder.encode(cb, buffer, true)
            if (r.isUnmappable) r.throwException()
            if (r.isOverflow) {
                pushBack = cb
                break
            }
        }

        buffer.flip()
        writeImpl(destination, pool, buffer)
    }
}

private suspend fun writeImpl(socket: WriteChannel, pool: Channel<ByteBuffer>, buffer: ByteBuffer) {
    try {
        while (buffer.hasRemaining()) {
            socket.write(buffer)
        }
    } finally {
        pool.offer(buffer)
    }
}

/**
 * Opens a channel of accepted sockets of given [capacity] and starts producer job to accept connections and populate it.
 */
fun <T : ASocket> Acceptable<T>.openAcceptChannel(capacity: Int = 100): ProducerJob<T> {
    return openAcceptChannel(capacity) { it }
}

/**
 * Opens a channel of accepted sockets transformed by [transform] function of given [capacity] and starts producer job
 * to accept connections and populate it.
 */
fun <T : ASocket, S : Acceptable<T>, R> S.openAcceptChannel(capacity: Int = 100, transform: S.(T) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        acceptorLoop(this@openAcceptChannel, this, transform)
    }
}

/**
 * Creates a job to accept connections and put them to [destination] channel.
 * @return a job that hasn't been started yet
 */
fun <T : ASocket, S : Acceptable<T>, R> S.acceptSocketsTo(destination: SendChannel<R>, transform: S.(T) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        acceptorLoop(this@acceptSocketsTo, destination, transform)
    }
}

private suspend fun <T : ASocket, S : Acceptable<T>, R> acceptorLoop(source: S, destination: SendChannel<R>, transform: S.(T) -> R) {
    while (true) {
        val e = try { source.accept() } catch (e: ClosedChannelException) { break } catch (e: CancelledKeyException) { break }

        try {
            destination.send(transform(source, e))
        } catch (t: Throwable) {
            e.close()
            throw t
        }
    }
}

/**
 * Opens channel of input addresses to connect to and a channel of connected sockets and starts processing job.
 * Every socket is configured by [configure] function before connect.
 */
fun openConnector(configure: Configurable<*>.(SocketAddress) -> Unit = {}, selector: SelectorManager = SelectorManager.DefaultSelectorManager): Pair<SendChannel<SocketAddress>, ReceiveChannel<Socket>> {
    return openConnector({ it }, { _, s -> s }, configure, selector)
}

/**
 * Opens channel of input addresses to connect to (consists of elements of type [A])
 * and a channel of connected sockets (elements of type [R]) and starts processing job.
 * Every input element of type [A] is mapped by [inTransform] function to a [SocketAddress].
 * Every socket is configured by [configure] function before connect and mapped to an element of type [R] after connect
 * that will be sent to the resulting channel.
 *
 * @return a pair of send and receive channels associated with the started job.
 */
fun <A, R> openConnector(inTransform: (A) -> SocketAddress,
                         outTransform: (A, Socket) -> R,
                         configure: Configurable<*>.(A) -> Unit = {},
                         selector: SelectorManager = SelectorManager.DefaultSelectorManager): Pair<SendChannel<A>, ReceiveChannel<R>> {

    val source = ArrayChannel<A>(1000)
    val destination = ArrayChannel<R>(1000)

    launch(ioCoroutineDispatcher) {
        connectorLoop(selector, source, destination, inTransform, outTransform, configure)
    }.invokeOnCompletion { t ->
        source.close(t)
        destination.close(t)
    }

    return Pair(source, destination)
}

private suspend fun <A, R> connectorLoop(selector: SelectorManager,
                                         source: ReceiveChannel<A>,
                                         destination: SendChannel<R>,
                                         inTransform: (A) -> SocketAddress,
                                         outTransform: (A, Socket) -> R,
                                         configure: Configurable<*>.(A) -> Unit) {

    val runningCounter = AtomicLong()
    val latch = ConflatedChannel<Boolean>()

    while (true) {
        val src = source.receiveOrNull() ?: break

        runningCounter.incrementAndGet()
        launch(ioCoroutineDispatcher) {
            val address = inTransform(src)
            val socket = aSocket(selector).tcp()
            configure(socket, src)

            val connected = socket.connect(address)
            try {
                destination.send(outTransform(src, connected))
            } catch (t: Throwable) {
                connected.close()
                throw t
            }
        }.invokeOnCompletion { if (runningCounter.decrementAndGet() == 0L) launch(ioCoroutineDispatcher) { latch.send(true) } }
    }

    while (runningCounter.get() > 0) {
        latch.receiveOrNull() ?: break
    }
}