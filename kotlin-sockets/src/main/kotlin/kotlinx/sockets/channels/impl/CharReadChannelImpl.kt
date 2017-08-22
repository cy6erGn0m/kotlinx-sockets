package kotlinx.sockets.channels.impl

import kotlinx.sockets.*
import kotlinx.sockets.channels.*
import java.nio.*
import java.nio.charset.*

@Deprecated("", level = DeprecationLevel.ERROR)
fun ReadChannel.asCharChannel(charset: Charset = Charsets.UTF_8,
                              buffer: ByteBuffer = ByteBuffer.allocate(8192)): CharReadChannel = TODO()

@Deprecated("", level = DeprecationLevel.ERROR)
suspend fun CharReadChannel.readLine(buffer: CharBuffer): Pair<String, CharBuffer> =
        TODO()

@Deprecated("")
suspend fun <A : Appendable> CharReadChannel.readLineTo(out: A, buffer: CharBuffer): Pair<A, CharBuffer> {
    TODO()
}

@Deprecated("Use openReadChannel instead", level = DeprecationLevel.ERROR)
fun AReadable.asCharChannel(): CharReadChannel = TODO()

@Deprecated("Use openWriteChannel instead", level = DeprecationLevel.ERROR)
fun AWritable.asCharWriteChannel(): CharWriteChannel = TODO()

@Deprecated("Use openReadChannel instead", level = DeprecationLevel.ERROR)
fun CharReadChannel.buffered(): BufferedCharReadChannel = TODO()
