package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel
import java.nio.*
import java.nio.channels.*
import java.nio.charset.*

fun ReadChannel.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<ByteBuffer> {
    return openReceiveChannel(pool, capacity) { it }
}

fun <C : ReadChannel, R> C.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(ByteBuffer) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveLoop(this@openReceiveChannel, channel, pool, transform)
    }
}

fun ReadChannel.receiveTo(channel: SendChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return receiveTo(channel, pool, { it })
}

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

fun ReadChannel.receiveLinesTo(destination: SendChannel<String>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return receiveLinesTo(destination, charset, pool) { it }
}

fun <C : ReadChannel, R> C.receiveLinesTo(destination: SendChannel<R>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(String) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        receiveLinesLoop(this@receiveLinesTo, destination, charset, pool, transform)
    }
}

fun ReadChannel.openLinesReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    return openLinesReceiveChannel(charset, pool, capacity) { it }
}

fun <C : ReadChannel, R> C.openLinesReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(String) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveLinesLoop(this@openLinesReceiveChannel, this, charset, pool, transform)
    }
}

fun ReadChannel.openTextReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<String> {
    return openTextReceiveChannel(charset, pool, capacity) { it }
}

fun <C : ReadChannel, R> C.openTextReceiveChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(String) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveTextLoop(this@openTextReceiveChannel, this, charset, pool, transform)
    }
}

fun ReadChannel.receiveTextTo(destination: SendChannel<String>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return receiveTextTo(destination, charset, pool) { it }
}

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

fun WriteChannel.openSendChannel(pool: Channel<ByteBuffer>, capacity: Int = 2): ActorJob<ByteBuffer> {
    return openSendChannel(pool, capacity) { it }
}

fun <C : WriteChannel, T> C.openSendChannel(pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(T) -> ByteBuffer): ActorJob<T> {
    return actor(ioCoroutineDispatcher, capacity) {
        writeLoop(this@openSendChannel, this, pool, transform)
    }
}

fun WriteChannel.sendFrom(source: ReceiveChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return sendFrom(source, pool, { it })
}

fun <C : WriteChannel, T> C.sendFrom(source: ReceiveChannel<T>, pool: Channel<ByteBuffer>, transform: C.(T) -> ByteBuffer): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        writeLoop(this@sendFrom, source, pool, transform)
    }
}

fun WriteChannel.sendTextFrom(channel: ReceiveChannel<CharSequence>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return sendTextFrom(channel, charset, pool, { it })
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

fun WriteChannel.openTextSendChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2): ActorJob<CharSequence> {
    return openTextSendChannel(charset, pool, capacity) { it }
}

fun <C : WriteChannel, T> C.openTextSendChannel(charset: Charset, pool: Channel<ByteBuffer>, capacity: Int = 2, transform: C.(T) -> CharSequence): ActorJob<T> {
    return actor(ioCoroutineDispatcher, capacity) {
        encodeAndWriteLoop(this@openTextSendChannel, this, charset, pool, transform)
    }
}

fun <C : WriteChannel, T> C.sendTextFrom(source: ReceiveChannel<T>, charset: Charset, pool: Channel<ByteBuffer>, transform: C.(T) -> CharSequence): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        encodeAndWriteLoop(this@sendTextFrom, source, charset, pool, transform)
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

fun <T : ASocket> SocketSource<T>.openAcceptChannel(capacity: Int = 100): ProducerJob<T> {
    return openAcceptChannel(capacity) { it }
}

fun <T : ASocket, S : SocketSource<T>, R> S.openAcceptChannel(capacity: Int = 100, transform: S.(T) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        acceptorLoop(this@openAcceptChannel, this, transform)
    }
}

fun <T : ASocket, S : SocketSource<T>, R> S.acceptSocketsTo(destination: SendChannel<R>, transform: S.(T) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        acceptorLoop(this@acceptSocketsTo, destination, transform)
    }
}

private suspend fun <T : ASocket, S : SocketSource<T>, R> acceptorLoop(source: S, destination: SendChannel<R>, transform: S.(T) -> R) {
    while (true) {
        val e = try { source.accept() } catch (e: ClosedChannelException) { break }

        try {
            destination.send(transform(source, e))
        } catch (t: Throwable) {
            e.close()
            throw t
        }
    }
}