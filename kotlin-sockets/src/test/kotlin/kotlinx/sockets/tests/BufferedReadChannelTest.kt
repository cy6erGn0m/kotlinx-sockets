package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.adapters.*
import org.junit.*
import java.nio.*
import kotlin.test.*

class BufferedReadChannelTest {
    private val pool = Channel<ByteBuffer>(10)

    @Test
    fun testFill() {
        val ch = Channel<ByteBuffer>(1)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))

            buffered.fill(0)
            assertTrue { ch.isEmpty }
            assertEquals('A', buffered.getByte().toChar())

            ch.close()
            // no more data available from this point

            buffered.fill(0) // shouldn't fail
            try {
                buffered.fill(1)
                fail("should fail")
            } catch (expected: BufferUnderflowException) {
            }
        }
    }

    @Test
    fun testFillMultipleTimes() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B".toByteArray()))
            ch.send(ByteBuffer.wrap("C".toByteArray()))

            buffered.fill(3)
            assertTrue { ch.isEmpty }
            assertEquals("ABC", buffered.getStringByRawLength(3, Charsets.ISO_8859_1.newDecoder()))
        }
    }

    @Test
    fun testGetStringByRawLength() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B".toByteArray()))
            ch.send(ByteBuffer.wrap("C".toByteArray()))

            assertEquals("ABC", buffered.getStringByRawLength(3, Charsets.ISO_8859_1.newDecoder()))
        }
    }

    @Test
    fun testReadASCIILineLf() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B\n".toByteArray()))
            ch.send(ByteBuffer.wrap("C".toByteArray()))

            ch.close()

            assertEquals("AB", buffered.readASCIILine())
            assertEquals("C", buffered.readASCIILine())
            assertEquals(null, buffered.readASCIILine())
        }
    }

    @Test
    fun testReadASCIILineCrLf() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B\r\n".toByteArray()))
            ch.send(ByteBuffer.wrap("C".toByteArray()))

            ch.close()

            assertEquals("AB", buffered.readASCIILine())
            assertEquals("C", buffered.readASCIILine())
            assertEquals(null, buffered.readASCIILine())
        }
    }

    @Test
    fun testReadASCIILineCrLfBadSplit() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B\r".toByteArray()))
            ch.send(ByteBuffer.wrap("\nC".toByteArray()))

            ch.close()

            assertEquals("AB", buffered.readASCIILine())
            assertEquals("C", buffered.readASCIILine())
            assertEquals(null, buffered.readASCIILine())
        }
    }

    @Test
    fun testReadASCIILineTrailingLf() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("A".toByteArray()))
            ch.send(ByteBuffer.wrap("B\n".toByteArray()))
            ch.send(ByteBuffer.wrap("C\n".toByteArray()))

            ch.close()

            assertEquals("AB", buffered.readASCIILine())
            assertEquals("C", buffered.readASCIILine())
            assertEquals(null, buffered.readASCIILine())
        }
    }

    @Test
    fun testReadASCIILineLeadingLf() {
        fillPool(3)
        val ch = Channel<ByteBuffer>(3)
        val buffered = ReceiveChannelBufferedReadChannel(ch, pool, ByteOrder.nativeOrder())

        runBlocking {
            ch.send(ByteBuffer.wrap("\nA".toByteArray()))
            ch.send(ByteBuffer.wrap("B\n".toByteArray()))
            ch.send(ByteBuffer.wrap("C".toByteArray()))

            ch.close()

            assertEquals("", buffered.readASCIILine())
            assertEquals("AB", buffered.readASCIILine())
            assertEquals("C", buffered.readASCIILine())
            assertEquals(null, buffered.readASCIILine())
        }
    }

    private fun fillPool(count: Int) {
        for (i in 1..count) {
            pool.offer(ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE))
        }
    }
}