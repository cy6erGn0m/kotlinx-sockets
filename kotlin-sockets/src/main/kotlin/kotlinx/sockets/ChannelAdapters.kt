package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

fun ReadChannel.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2): ProducerJob<ByteBuffer> {
    return openReceiveChannel(pool, capacity) { it }
}

fun <C : ReadChannel, R> C.openReceiveChannel(pool: Channel<ByteBuffer>, capacity: Int = 2, onReceive: C.(ByteBuffer) -> R): ProducerJob<R> {
    return produce(ioCoroutineDispatcher, capacity) {
        receiveLoop(this@openReceiveChannel, channel, pool, onReceive)
    }
}

fun ReadChannel.receiveTo(channel: SendChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return receiveTo(channel, pool, { it })
}

fun <C : ReadChannel, R> C.receiveTo(channel: SendChannel<R>, pool: Channel<ByteBuffer>, onReceive: C.(ByteBuffer) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        receiveLoop(this@receiveTo, channel, pool, onReceive)
    }
}

private suspend fun <C : ReadChannel, R> receiveLoop(source: C, channel: SendChannel<R>, pool: Channel<ByteBuffer>, onReceive: C.(ByteBuffer) -> R) {
    while (true) {
        val bb = pool.receiveOrNull() ?: break
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
            channel.send(onReceive(source, bb))
        }
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
        val e = source.accept()

        try {
            destination.send(transform(source, e))
        } catch (t: Throwable) {
            e.close()
            throw t
        }
    }
}