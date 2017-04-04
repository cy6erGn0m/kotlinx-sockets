package kotlinx.sockets.channels.impl

import kotlinx.sockets.channels.*
import java.nio.*
import java.nio.charset.*

internal class CharReadChannelImpl(val source: ReadChannel, val charset: Charset, val buffer: ByteBuffer) : CharReadChannel {
    private val decoder by lazy { charset.newDecoder()!! }

    suspend override fun read(dst: CharBuffer): Int {
        val before = dst.position()
        val rc = source.read(buffer)

        buffer.flip()
        val result = decoder.decode(buffer, dst, rc == -1)
        buffer.compact()

        if (result.isMalformed || result.isUnmappable) {
            result.throwException()
        }
        val resultSize = dst.position() - before

        return when {
            rc == -1 -> when {
                buffer.position() == 0 && resultSize == 0 -> -1
                else -> resultSize
            }
            resultSize == 0 && dst.hasRemaining() -> read(dst)
            else -> resultSize
        }
    }
}

/**
 * Creates a character channel from regular bytes channel
 */
fun ReadChannel.asCharChannel(charset: Charset = Charsets.UTF_8,
                              buffer: ByteBuffer = ByteBuffer.allocate(8192)): CharReadChannel = CharReadChannelImpl(this, charset, buffer)

/**
 * Reads line from the channel using [buffer].
 * It is very important that [buffer] could contain characters before and after function invocation.
 * If you create new buffer then you have to set position/limit accordingly otherwise you get all zeroes from the [buffer].
 * If there are characters in the [buffer] before invocation than they will be used before read from the channel.
 * Characters remaining in the [buffer] after invocations should be never ignored/discarded. You can use the same buffer
 * to call [readLineTo] multiple times, in this case you can simply pass as argument with no position change.
 *
 * [readLineTo] appends characters to a [StringBuilder] and suspends when
 * needed until end of line or end of stream reached.
 *
 * @return a pair of line string and the [buffer]
 */
suspend fun CharReadChannel.readLine(buffer: CharBuffer): Pair<String, CharBuffer> =
        readLineTo(StringBuilder(), buffer)
                .let { Pair(it.first.toString(), it.second) }

/**
 * Reads line from the channel to given [out] using [buffer].
 * It is very important that [buffer] could contain characters before and after function invocation.
 * If you create new buffer than you have to set position/limit accordingly otherwise all zeroes from the [buffer]
 * will be appended to [out].
 * If there are characters in the [buffer] before invocation than they will be used before read from the channel.
 * Characters remaining in the [buffer] after invocations should be never ignored/discarded. You can use the same buffer
 * to call [readLineTo] multiple times, in this case you can simply pass as argument with no position change.
 *
 * [readLineTo] appends characters to the given [out] and suspends when needed until end of line or end of stream reached.
 * @return a pair of the given appendable [out] and the [buffer]
 */
suspend fun <A : Appendable> CharReadChannel.readLineTo(out: A, buffer: CharBuffer): Pair<A, CharBuffer> {
    while (true) {
        val eof = fill(buffer)

        val cr = buffer.charBufferIndexOf('\n')
        val lf = buffer.charBufferIndexOf('\r')
        val crOrLf = when {
            cr == -1 && lf == -1 -> -1
            cr == -1 -> lf
            lf == -1 -> cr
            else -> minOf(cr, lf)
        }

        val start = buffer.position()

        when {
            crOrLf == -1 && eof -> {
                out.charBufferAppend(buffer, start, buffer.limit())
                buffer.position(buffer.limit())

                return Pair(out, buffer)
            }
            crOrLf == -1 -> {
                out.charBufferAppend(buffer, start, buffer.limit())
                buffer.position(buffer.limit())
            }
            else -> {
                out.charBufferAppend(buffer, start, crOrLf)

                if (buffer[crOrLf] == '\r') {
                    buffer.position(crOrLf + 1)
                    if (!buffer.hasRemaining()) {
                        fill(buffer)
                    }

                    when {
                        buffer.hasRemaining() && buffer[buffer.position()] == '\n' -> buffer.get()
                        else -> Unit // do nothing as we can only reach here at eof
                    }
                } else {
                    buffer.position(crOrLf + 1)
                }

                return Pair(out, buffer)
            }
        }
    }
}

private fun Appendable.charBufferAppend(buffer: CharBuffer, start: Int, end: Int) {
    for (idx in start .. end - 1) {
        append(buffer.get(idx))
    }
}

private fun CharBuffer.charBufferIndexOf(char: Char): Int {
    for (idx in position()..limit() - 1) {
        if (get(idx) == char) return idx
    }

    return -1
}

internal suspend fun CharReadChannel.fill(buffer: CharBuffer): Boolean {
    if (!buffer.hasRemaining()) {
        buffer.compact()
        val rc = read(buffer)
        buffer.flip()

        return rc == -1
    }

    return false
}
