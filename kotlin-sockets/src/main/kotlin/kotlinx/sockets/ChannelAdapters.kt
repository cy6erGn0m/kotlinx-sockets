package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.nio.*
import java.nio.charset.*

fun ReadChannel.receiveTo(channel: Channel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        while (!channel.isClosedForSend) {
            val bb = pool.receive()
            bb.clear()

            val rc = try {
                this@receiveTo.read(bb)
            } catch (t: Throwable) {
                pool.send(bb)
                channel.close(t)
                throw t
            }

            if (rc == -1) {
                channel.close()
                pool.send(bb)
                break
            } else {
                bb.flip()
                channel.send(bb)
            }
        }
    }.apply {
        invokeOnCompletion { t -> channel.close(t) }
    }
}

fun WriteChannel.sendFrom(channel: Channel<ByteBuffer>, pool: Channel<ByteBuffer>): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        var pushBack: ByteBuffer? = null

        while (!channel.isClosedForReceive) {
            val first = pushBack ?: channel.receiveOrNull() ?: break
            var out: ByteBuffer? = null

            if (first.remaining() < 8192) {
                while (out == null || out.hasRemaining()) {
                    val more = channel.poll() ?: break

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
    }.apply {
        invokeOnCompletion { t ->
            channel.close(t)
        }
    }
}

fun WriteChannel.sendFrom(channel: Channel<String>, charset: Charset, pool: Channel<ByteBuffer>): Job {
    return launch(ioCoroutineDispatcher, start = false) {
        val encoder = charset.newEncoder()!!
        var pushBack: CharBuffer? = null

        while (!channel.isClosedForReceive) {
            var cb = pushBack ?: channel.receiveOrNull()?.let { CharBuffer.wrap(it) } ?: break
            val buffer = pool.receive()
            buffer.clear()

            val r = encoder.encode(cb, buffer, true)
            if (r.isUnmappable) r.throwException()

            while (!cb.hasRemaining() && buffer.hasRemaining()) {
                cb = channel.poll()?.let { CharBuffer.wrap(it) } ?: break

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
    }.apply {
        invokeOnCompletion { t ->
            channel.close(t)
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
