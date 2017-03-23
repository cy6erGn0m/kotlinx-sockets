package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

fun ReadChannel.receiveTo(channel: SendChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return receiveTo(channel, pool, { it })
}

fun <C : ReadChannel, R> C.receiveTo(channel: SendChannel<R>, pool: Channel<ByteBuffer>, onReceive: C.(ByteBuffer) -> R): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        while (!channel.isClosedForSend) {
            val bb = pool.receive()
            bb.clear()

            val rc = try {
                this@receiveTo.read(bb)
            } catch (t: Throwable) {
                pool.offer(bb)
                channel.close(t)
                throw t
            }

            if (rc == -1) {
                channel.close()
                pool.offer(bb)
                break
            } else {
                bb.flip()
                channel.send(onReceive(bb))
            }
        }
    }.apply {
        invokeOnCompletion { t -> channel.close(t) }
    }
}

fun WriteChannel.sendFrom(channel: ReceiveChannel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return sendFrom(channel, pool, { it })
}

fun <C : WriteChannel, T> C.sendFrom(channel: ReceiveChannel<T>, pool: Channel<ByteBuffer>, onSend: C.(T) -> ByteBuffer): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        var pushBack: ByteBuffer? = null

        while (!channel.isClosedForReceive) {
            val first = pushBack ?: channel.receiveOrNull()?.let { onSend(it) } ?: break
            var out: ByteBuffer? = null

            if (first.remaining() < 8192) {
                while (out == null || out.hasRemaining()) {
                    val more = channel.poll()?.let { onSend(it) } ?: break

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
                writeImpl(this@sendFrom, pool, out)
                pool.offer(out)
            } else {
                writeImpl(this@sendFrom, pool, first)
                pool.offer(first)
            }
        }

        if (pushBack != null) {
            writeImpl(this@sendFrom, pool, pushBack)
            pool.offer(pushBack)
        }
    }
}

fun WriteChannel.sendFrom(channel: ReceiveChannel<CharSequence>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return sendFrom(channel, charset, pool, { it })
}

fun <C : WriteChannel, T> C.sendFrom(channel: ReceiveChannel<T>, charset: Charset, pool: Channel<ByteBuffer>, onSend: C.(T) -> CharSequence): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        val encoder = charset.newEncoder()!!
        var pushBack: CharBuffer? = null

        while (!channel.isClosedForReceive) {
            var cb = pushBack ?: channel.receiveOrNull()?.let { CharBuffer.wrap(onSend(it)) } ?: break
            val buffer = pool.receive()
            buffer.clear()

            val r = encoder.encode(cb, buffer, true)
            if (r.isUnmappable) r.throwException()

            while (!cb.hasRemaining() && buffer.hasRemaining()) {
                cb = channel.poll()?.let { CharBuffer.wrap(onSend(it)) } ?: break

                encoder.encode(cb, buffer, true)
                if (r.isUnmappable) r.throwException()
                if (r.isOverflow) {
                    pushBack = cb
                    break
                }
            }

            buffer.flip()
            writeImpl(this@sendFrom, pool, buffer)
        }
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
