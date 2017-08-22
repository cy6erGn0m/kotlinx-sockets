package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import org.junit.*
import org.junit.rules.*
import java.util.concurrent.*
import kotlin.test.*

class CharChannelTest {
    @get:Rule
    val timeout = Timeout(15L, TimeUnit.SECONDS)

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
    fun testBufferedReadLine() {
        testReadLineBuffered("") {
            assertNull(readUTF8Line())
            assertNull(readUTF8Line())
        }

        testReadLineBuffered("a") {
            assertEquals("a", readUTF8Line())
            assertNull(readUTF8Line( ))
        }

        testReadLineBuffered("a\n") {
            assertEquals("a", readUTF8Line())
//            assertEquals("", readLine())
            assertNull(readUTF8Line( ))
        }

        testReadLineBuffered("a\nb") {
            assertEquals("a", readUTF8Line())
            assertEquals("b", readUTF8Line())
            assertNull(readUTF8Line( ))
        }

        testReadLineBuffered("aa\nbb\n") {
            assertEquals("aa", readUTF8Line())
            assertEquals("bb", readUTF8Line())
//            assertEquals("", readLine())
            assertNull(readUTF8Line( ))
        }

        testReadLineBuffered("a\nb") {
            assertEquals("a", readUTF8Line())
            assertEquals('b', readByte().toChar())
        }
    }

    private fun testReadLine(source: String, expected: String, remaining: String, bufferSize: Int = 8192) {
        runBlocking {
            val ch = ByteReadChannel(source.toByteArray())
            val line = ch.readUTF8Line().orEmpty()
            val bb = ByteBuffer.allocate(bufferSize)
            ch.readAvailable(bb)
            bb.flip()
            val rem = Charsets.UTF_8.decode(bb)

            assertEquals(expected, line, "line text")
            assertEquals(remaining, rem.toString(), "remaining buffer")
        }
    }

    private fun testReadLineBuffered(source: String, block: suspend ByteReadChannel.() -> Unit) {
        runBlocking {
            block(ByteReadChannel(source.toByteArray()))
        }
    }
}