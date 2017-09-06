package kotlinx.http

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.impl.*
import java.io.*

private const val MAX_CHUNK_SIZE_LENGTH = 128
private const val CHUNK_BUFFER_POOL_SIZE = 2048

private val ChunkSizeBufferPool: ObjectPool<StringBuilder> = object: ObjectPoolImpl<StringBuilder>(CHUNK_BUFFER_POOL_SIZE) {
    override fun produceInstance(): StringBuilder = StringBuilder(MAX_CHUNK_SIZE_LENGTH)
    override fun clearInstance(instance: StringBuilder) = instance.delete(0, instance.length)
}

suspend fun decodeChunked(input: ByteReadChannel): ByteReadChannel {
    val out = ByteChannel()
    launch(ioCoroutineDispatcher, start = CoroutineStart.UNDISPATCHED) {
        decodeChunked(input, out)
    }
    return out
}

suspend fun decodeChunked(input: ByteReadChannel, out: ByteWriteChannel) {
    val chunkSizeBuffer = ChunkSizeBufferPool.borrow()

    try {
        while (true) {
            chunkSizeBuffer.clear()
            if (!input.readUTF8LineTo(chunkSizeBuffer, MAX_CHUNK_SIZE_LENGTH)) {
                throw EOFException("Chunked stream has ended unexpectedly: no chunk size")
            } else if (chunkSizeBuffer.isEmpty()) {
                throw EOFException("Invalid chunk size: empty")
            }

            if (chunkSizeBuffer.length == 1 && chunkSizeBuffer[0] == '0') break
            val chunkSize = chunkSizeBuffer.parseHexLong()
            input.copyTo(out, chunkSize)
            out.flush()

            chunkSizeBuffer.clear()
            if (!input.readUTF8LineTo(chunkSizeBuffer, 2)) {
                throw EOFException("Invalid chunk: content block ended unexpectedly")
            }
            if (chunkSizeBuffer.isNotEmpty()) {
                throw EOFException("Invalid chunk: content block should end with CR+LF")
            }
        }
    } catch (t: Throwable) {
        out.close(t)
        throw t
    } finally {
        ChunkSizeBufferPool.recycle(chunkSizeBuffer)
        out.close()
    }
}

suspend fun encodeChunked(output: ByteWriteChannel): ByteWriteChannel {
    val raw = ByteChannel()
    launch(ioCoroutineDispatcher) {
        try {
            encodeChunked(raw, output)
        } catch (t: Throwable) {
            output.close(t)
            raw.close(t)
        }
    }

    return raw
}

private val CrLf = "\r\n".toByteArray()
private val LastChunkBytes = "0\r\n".toByteArray()
suspend fun encodeChunked(input: ByteReadChannel, output: ByteWriteChannel) {
    val chunkSizeBuffer = ChunkSizeBufferPool.borrow()
    val buffer = DefaultByteBufferPool.borrow()

    try {
        while (true) {
            val size = input.readAvailable(buffer)
            if (size == -1) {
                break
            }

            buffer.flip()
            chunkSizeBuffer.append(size.toString(16))
            chunkSizeBuffer.append("\r\n")
            output.writeStringUtf8(chunkSizeBuffer)
            output.writeFully(buffer)
            output.writeFully(CrLf)
            output.flush()

            chunkSizeBuffer.clear()
            buffer.clear()
        }

        output.writeFully(LastChunkBytes)
    } catch (t: Throwable) {
        output.close(t)
    } finally {
        output.flush()
        DefaultByteBufferPool.recycle(buffer)
        ChunkSizeBufferPool.recycle(chunkSizeBuffer)
    }
}

private fun StringBuilder.clear() {
    delete(0, length)
}