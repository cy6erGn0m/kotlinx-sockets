package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*

fun ByteWritePacket.writeTLSHeader(header: TLSHeader) {
    writeByte(header.type.code.toByte())
    writeShort(header.version.code.toShort())
    writeShort(header.length.toShort())
}

fun ByteWritePacket.writeTLSHandshake(handshake: TLSHandshakeHeader) {
    if (handshake.length > 0xffffff) throw TLSException("TLS handshake size limit exceeded: ${handshake.length}")
    val v = (handshake.type.code shl 24) or handshake.length
    writeInt(v)
}

fun ByteWritePacket.writeTLSClientHello(hello: TLSHandshakeHeader) {
    writeShort(hello.version.code.toShort())
    writeFully(hello.random)

    if (hello.sessionIdLength < 0 || hello.sessionIdLength > 0xff || hello.sessionIdLength > hello.sessionId.size) throw TLSException("Illegal sessionIdLength")
    writeByte(hello.sessionIdLength.toByte())
    writeFully(hello.sessionId, 0, hello.sessionIdLength)

    writeShort((hello.suitesCount * 2).toShort())
    val suites = hello.suites
    for (i in 0 until hello.suitesCount) {
        writeShort(suites[i])
    }

    // compression is always null
    writeByte(1)
    writeByte(0)

    // extensions are always null
    writeShort(0)
}

fun main(args: Array<String>) = runBlocking {
    ActorSelectorManager().use { selector ->
        aSocket(selector).tcp().connect(InetSocketAddress(InetAddress.getByName("ya.ru"), 443)).use { socket ->
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel()


            val handshake = TLSHandshakeHeader()
            handshake.type = TLSHandshakeType.ClientHello
            handshake.suitesCount = 1
            handshake.suites[0] = 0x009d

            handshake.apply {
                length = 2 + 32 + 1 + suitesCount * 2 + 1 + sessionIdLength + 3 + 2
            }

            val handshakePacket = WritePacket()
            handshakePacket.writeTLSHandshake(handshake)
            handshakePacket.writeTLSClientHello(handshake)

            val header = TLSHeader()
            header.type = RecordType.Handshake
            header.length = handshakePacket.size

            output.writePacket {
                writeTLSHeader(header)
            }

            output.writePacket(handshakePacket.build())
            output.flush()

            FileChannel.open(Paths.get("dump.dat"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { pc ->
                input.consumeEachBufferRange { buffer, _ ->
                    while (buffer.hasRemaining()) {
                        pc.write(buffer)
                    }
                    pc.force(true)

                    true
                }
            }
        }
    }
}