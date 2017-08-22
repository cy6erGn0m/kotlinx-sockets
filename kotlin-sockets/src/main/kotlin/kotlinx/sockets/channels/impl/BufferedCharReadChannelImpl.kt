package kotlinx.sockets.channels.impl

import kotlinx.sockets.channels.*
import java.nio.*

/**
 * Creates buffered char read channel with buffer of [size] chars.
 * [bufferSupplier] is useful
 */
fun CharReadChannel.buffered(size: Int = 8192, bufferSupplier: (Int) -> CharBuffer = CharBuffer::allocate): BufferedCharReadChannel = TODO()