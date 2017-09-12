package kotlinx.http.tls

import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*
import java.io.*

class TLSHeader {
    var type: RecordType = RecordType.Handshake
    var version: TLSVersion = TLSVersion.TLS12
    var length: Int = 0
}

class TLSHandshakeHeader {
    var type: TLSHandshakeType = TLSHandshakeType.HelloRequest
    var length: Int = 0

    var version: TLSVersion = TLSVersion.TLS12

    var random = ByteArray(32)

    var suitesCount = 0
    var suites = ShortArray(255)

    var sessionIdLength = 0
    var sessionId = ByteArray(32)
}

suspend fun ByteReadChannel.readTLSHeader(header: TLSHeader) {
    header.type = RecordType.byCode(readByte().toInt() and 0xff)
    header.version = readTLSVersion()
    header.length = readShort().toInt() and 0xffff

    if (header.length > MAX_TLS_FRAME_SIZE) throw TLSException("Illegal TLS frame size: ${header.length}")
}

suspend fun ByteReadChannel.readTLSHandshake(header: TLSHeader, handshake: TLSHandshakeHeader) {
    if (header.type !== RecordType.Handshake) throw TLSException("Expected TLS handshake but got ${header.type}")

    val v = readInt()
    handshake.type = TLSHandshakeType.byCode(v ushr 24)
    handshake.length = v and 0xffffff
}

suspend fun ByteReadChannel.readTLSClientHello(header: TLSHeader, handshake: TLSHandshakeHeader) {
    readTLSHandshake(header, handshake)
    val p = readPacket(handshake.length)

    if (handshake.type !== TLSHandshakeType.ClientHello) throw TLSException("Expected TLS handshake ClientHello but got ${handshake.type}")

    handshake.version = p.readTLSVersion()
    p.readFully(handshake.random)
    val sessionIdLength = p.readByte().toInt() and 0xff

    if (sessionIdLength > 32) throw TLSException("sessionId length limit of 32 bytes exceeded: $sessionIdLength specified")
    handshake.sessionIdLength = sessionIdLength
    p.readFully(handshake.sessionId, 0, sessionIdLength)

    val cipherSuitesSize = p.readShort().toInt() and 0xffff
    val suitesCount = cipherSuitesSize / 2

    val suites = if (suitesCount > 255) ShortArray(cipherSuitesSize / 2).also { handshake.suites = it } else handshake.suites
    handshake.suitesCount = suitesCount

    for (i in 0 until suites.size) {
        suites[i] = p.readShort()
    }

    p.skipExact(3) // skip compression

    if (p.remaining > 0) {
        val extensionsLength = p.readUShort()
        p.skipExact(extensionsLength)

        // TODO TLS extensions
    }

    if (p.remaining > 0) {
        throw TLSException("TLS handshake extra bytes found")
    }
}



private const val MAX_TLS_FRAME_SIZE = 0x4800

class TLSException(message: String, cause: Throwable? = null) : IOException(message, cause)

private suspend fun ByteReadChannel.readTLSVersion() =
        TLSVersion.byCode(readShort().toInt() and 0xffff)

private fun ByteReadPacket.readTLSVersion() =
        TLSVersion.byCode(readShort().toInt() and 0xffff)
