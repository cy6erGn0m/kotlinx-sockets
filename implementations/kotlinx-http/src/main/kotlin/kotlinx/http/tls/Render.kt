package kotlinx.http.tls

import kotlinx.coroutines.experimental.io.packet.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*

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

fun ByteWritePacket.writeEncryptedPreMasterSecret(preSecret: ByteArray, publicKey: PublicKey, random: SecureRandom) {
    require(preSecret.size == 48)

    val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")!!
    rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
    val encryptedSecret = rsaCipher.doFinal(preSecret)

    if (encryptedSecret.size > 0xffff) throw TLSException("Encrypted premaster secret is too long")

    writeShort(encryptedSecret.size.toShort())
    writeFully(encryptedSecret)
}

fun ByteWritePacket.writeChangeCipherSpec(header: TLSHeader) {
    header.type = TLSRecordType.ChangeCipherSpec
    header.length = 1

    writeTLSHeader(header)
    writeByte(1)
}

internal fun finished(messages: List<ByteReadPacket>, baseHash: String, secretKey: SecretKeySpec): ByteReadPacket {
    val digestBytes = hashMessages(messages, baseHash)
    return finished(digestBytes, secretKey)
}

internal fun finished(digest: ByteArray, secretKey: SecretKey) = buildPacket {
    val prf = PRF(secretKey, CLIENT_FINISHED_LABEL, digest, 12)
    writeFully(prf)
}
