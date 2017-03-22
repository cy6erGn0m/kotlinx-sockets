package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import org.junit.*
import java.nio.*
import java.nio.channels.*
import kotlin.test.*

class CharChannelTest {
    companion object {
        val EmptyBuffer = CharBuffer.allocate(0)!!
    }

    @Test
    fun charReadChannel() {
        val rc = ByteArrayReadChannel("abc".toByteArray()).asCharChannel()
        val cb = CharBuffer.allocate(10)

        runBlocking {
            assertEquals(0, rc.read(EmptyBuffer))
            assertEquals(3, rc.read(cb))
            assertEquals(-1, rc.read(cb))
            assertEquals(-1, rc.read(EmptyBuffer))
            cb.flip()

            assertEquals("abc", cb.toString())
        }
    }

    @Test
    fun charReadChannelParts() {
        val rc = ByteArrayReadChannel("abc".toByteArray()).asCharChannel()
        val cb = CharBuffer.allocate(1)

        runBlocking {
            assertEquals(1, rc.read(cb))
            assertEquals('a', cb[0])

            cb.clear()
            assertEquals(1, rc.read(cb))
            assertEquals('b', cb[0])

            cb.clear()
            assertEquals(1, rc.read(cb))
            assertEquals('c', cb[0])

            cb.clear()
            assertEquals(-1, rc.read(cb))
        }
    }

    @Test
    fun charReadChannelReadLine() {
        testReadLine("abc", "abc", "")
        testReadLine("", "", "") // TODO should be null?

        testReadLine("\n", "", "")
        testReadLine("\r", "", "")
        testReadLine("\r\n", "", "")
        testReadLine("1\n", "1", "")
        testReadLine("1\r", "1", "")
        testReadLine("1\r\n", "1", "")

        testReadLine("\n2", "", "2")
        testReadLine("\r2", "", "2")
        testReadLine("\r\n2", "", "2")
        testReadLine("1\n2", "1", "2")
        testReadLine("1\r2", "1", "2")
        testReadLine("1\r\n2", "1", "2")
    }

    @Test
    fun testReachChannelReadLineBadLuck() {
        // if we have unfortunate buffer size then \r and \n could be divided so we have to ensure
        // that we are handling it properly (we shouldn't produce extra-lines in this case)

        testReadLine("1\r\n2", "1", "", bufferSize = 1)
        testReadLine("1\r\n2", "1", "2", bufferSize = 2)
        testReadLine("1\r\n2", "1", "", bufferSize = 3)

        testReadLine("1\r\n", "1", "", bufferSize = 1)
        testReadLine("1\r\n", "1", "", bufferSize = 2)
        testReadLine("1\r\n", "1", "", bufferSize = 3)
    }

    @Test
    fun testBufferedReadLine() {
        testReadLineBuffered("") {
            assertNull(readLine())
            assertNull(readLine())
        }

        testReadLineBuffered("a") {
            assertEquals("a", readLine())
            assertNull(readLine())
        }

        testReadLineBuffered("a\n") {
            assertEquals("a", readLine())
//            assertEquals("", readLine())
            assertNull(readLine())
        }

        testReadLineBuffered("a\nb") {
            assertEquals("a", readLine())
            assertEquals("b", readLine())
            assertNull(readLine())
        }

        testReadLineBuffered("aa\nbb\n") {
            assertEquals("aa", readLine())
            assertEquals("bb", readLine())
//            assertEquals("", readLine())
            assertNull(readLine())
        }

        testReadLineBuffered("a\nb") {
            assertEquals("a", readLine())
            assertEquals('b', read().toChar())
            assertEquals(-1, read())
        }
    }

    private fun testReadLine(source: String, expected: String, remaining: String, bufferSize: Int = 8192) {
        runBlocking {
            val (line, rem) = ByteArrayReadChannel(source.toByteArray()).asCharChannel().readLine(CharBuffer.allocate(bufferSize).apply { position(limit()) })
            assertEquals(expected, line, "line text")
            assertEquals(remaining, rem.toString(), "remaining buffer")
        }
    }

    private fun testReadLineBuffered(source: String, block: suspend BufferedCharReadChannel.() -> Unit) {
        runBlocking {
            val ch = ByteArrayReadChannel(source.toByteArray()).asCharChannel().buffered()
            block(ch)
        }
    }

    private class ByteArrayReadChannel(array: ByteArray) : ReadChannel {
        private var buffer: ByteArray? = array
        private var position = 0

        suspend override fun read(dst: ByteBuffer): Int {
            buffer?.let {
                val remaining = it.size - position
                if (remaining <= 0) return -1
                val size = minOf(dst.remaining(), remaining)
                dst.put(it, position, size)
                position += size

                return size
            }

            throw ClosedChannelException()
        }

        override fun close() {
            buffer = null
        }
    }
}