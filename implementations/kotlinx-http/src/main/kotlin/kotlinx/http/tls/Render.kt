package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.coroutines.experimental.*

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

fun ByteWritePacket.writeClientKeyExchange(header: TLSHandshakeHeader, length: Int) {
    header.type = TLSHandshakeType.ClientKeyExchange
    header.length = length


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
    header.type = RecordType.ChangeCipherSpec
    header.length = 1

    writeTLSHeader(header)
    writeByte(1)
}

internal fun clientKeyExchange(random: SecureRandom, handshake: TLSHandshakeHeader, publicKey: PublicKey, preSecret: ByteArray): ByteReadPacket {
    require(preSecret.size == 48)

    val secretPacket = WritePacket()

    secretPacket.writeEncryptedPreMasterSecret(preSecret, publicKey, random)

    handshake.type = TLSHandshakeType.ClientKeyExchange
    handshake.length = secretPacket.size

    return buildPacket {
        writeTLSHandshake(handshake)
        writePacket(secretPacket.build())
    }
}

internal fun hashMessages(messages: List<ByteReadPacket>, baseHash: String): ByteArray {
    return runBlocking {
        val md = MessageDigest.getInstance(baseHash)
        val digestBytes = ByteArray(md.digestLength)
        val digest = digest(md, CommonPool, digestBytes)
        for (m in messages) {
            digest.channel.writePacket(m)
        }
        digest.channel.close()
        digest.join()
        digestBytes
    }
}

internal fun finished(messages: List<ByteReadPacket>, baseHash: String, secretKey: SecretKeySpec): ByteReadPacket {
    val digestBytes = hashMessages(messages, baseHash)
    return finished(digestBytes, secretKey)
}

internal fun finished(digest: ByteArray, secretKey: SecretKey) = buildPacket {
    val prf = PRF(secretKey, CLIENT_FINISHED_LABEL, digest, 12)
    writeFully(prf)
}

enum class SecretExchangeType {
    RSA,
    DiffieHellman
}

class CipherSuite(val code: Short,
                  val name: String, val openSSLName: String,
                  val exchangeType: SecretExchangeType,
                  val jdkCipherName: String, val keyStrength: Int, val fixedIvLength: Int, val ivLength: Int, val cipherTagSizeInBytes: Int,
                  val macName: String, val macStrength: Int,
                  val hashName: String
                  ) {
    val keyStrengthInBytes = keyStrength / 8
    val macStrengthInBytes = macStrength / 8
}

internal val TLS_RSA_WITH_AES_128_GCM_SHA256 = CipherSuite(0x009c, "TLS_RSA_WITH_AES_128_GCM_SHA256", "AES128_GCM_SHA256", SecretExchangeType.RSA, "AES/GCM/NoPadding", 128, 4, 12, 16, "HmacSHA256", 0, "SHA-256")

private val cipherSuites = mapOf(0x009c.toShort() to TLS_RSA_WITH_AES_128_GCM_SHA256)

class TLSClientSession(val input: ByteReadChannel, val output: ByteWriteChannel) {
    public val appDataInput: ByteReadChannel get() = _appDataInput
    public val appDataOutput: ByteWriteChannel get() = _appDataOutput

    private val _appDataInput = ByteChannel()
    private val _appDataOutput = ByteChannel()

    private val header = TLSHeader()
    private val handshakeHeader = TLSHandshakeHeader()
    private val packetForHashing = WritePacket()
    private var hashing = true

    private var cipherSuite: CipherSuite? = null
    private var serverRandom: ByteArray = EmptyByteArray
    private var serverKey: PublicKey? = null
    private var clientRandom: ByteArray = EmptyByteArray

    private var preSecret = EmptyByteArray
    private var masterSecret: SecretKey? = null

    private var keyMaterial: ByteArray = EmptyByteArray

    private val random = SecureRandom.getInstanceStrong()

    suspend fun run() {
        tlsHandshakeAndNegotiation()
    }

    private suspend fun tlsHandshakeAndNegotiation() {
        initClientRandom()
        sendClientHello()

        loop@while (true) {
            if (!readTLSHeader()) throw TLSException("Handshake failed: premature end of stream")
            val packet = readPacket()

            when (header.type) {
                RecordType.Handshake -> {
                    packet.readTLSHandshake(handshakeHeader)
                    val hs = if (hashing && handshakeHeader.type != TLSHandshakeType.HelloRequest) {
                        packetForHashing.writeTLSHandshake(handshakeHeader)
                        val (p, copy) = packet.duplicate()
                        packetForHashing.writePacket(copy)
                        copy.release()
                        p
                    } else {
                        packet
                    }

                    handshake(hs)
                }
                RecordType.ChangeCipherSpec -> {
                    if (header.length != 1) throw TLSException("ChangeCipherSpec should contain just one byte but there are ${header.length}")
                    val flag = packet.readByte()
                    changeCipherSpec(flag)
                    break@loop
                }
                else -> {
                    throw TLSException("Unsupported TLS record type ${header.type}")
                }
            }
        }

        launch(CommonPool) {
            try {
                appDataOutputLoop()
            } catch (t: Throwable) {
                _appDataOutput.close(t)
            } finally {
                _appDataOutput.close()
            }
        }

        try {
            appDataInputLoop()
        } catch (t: Throwable) {
            _appDataInput.close(t)
        } finally {
            _appDataInput.close()
        }
    }

    private suspend fun appDataInputLoop() {
        var seq = 1L
        while (true) {
            if (!readTLSHeader()) break
            val encrypted = readPacket()

            when (header.type) {
                RecordType.ApplicationData -> {
                    val recordIv = encrypted.readLong()
                    val cipher = decryptCipher(cipherSuite!!, keyMaterial, header.type, header.length, recordIv, seq)
                    val packet = encrypted.decrypted(cipher)

                    _appDataInput.writePacket(packet)
                    _appDataInput.flush()
                }
                RecordType.Alert -> {
                    val recordIv = encrypted.readLong()
                    val cipher = decryptCipher(cipherSuite!!, keyMaterial, header.type, header.length, recordIv, seq)
                    val packet = encrypted.decrypted(cipher)

                    val fatal = packet.readByte() == 2.toByte()
                    val code = packet.readByte()

                    if (fatal) {
                        _appDataInput.close(TLSException("Fatal: server alerted with description code $code"))
                    } else {
                        println("Got TLS warning $code")
                        _appDataInput.close()
                    }
                    return
                }
                else -> throw TLSException("Unexpected record ${header.type} (${header.length} bytes)")
            }

            seq ++
        }
    }

    private suspend fun appDataOutputLoop() {
        var seq = 1L
        val buffer = DefaultByteBufferPool.borrow()

        try {
            while (true) {
                buffer.clear()
                val rc = _appDataOutput.readAvailable(buffer)
                if (rc == -1) break

                buffer.flip()
                val cipher = encryptCipher(cipherSuite!!, keyMaterial, RecordType.ApplicationData, rc, seq, seq)
                val packet = buildPacket {
                    writeFully(buffer)
                }
                val encrypted = packet.encrypted(cipher, seq)
                output.writePacket {
                    header.type = RecordType.ApplicationData
                    header.version = TLSVersion.TLS12
                    header.length = encrypted.remaining
                    writeTLSHeader(header)
                }
                output.writePacket(encrypted)
                output.flush()

                seq++
            }
        } finally {
            DefaultByteBufferPool.recycle(buffer)
        }
    }

    private fun initClientRandom() {
        clientRandom = random.generateSeed(32).apply {
            val unixTime = (System.currentTimeMillis() / 1000L)
            this[0] = (unixTime shr 24).toByte()
            this[1] = (unixTime shr 16).toByte()
            this[2] = (unixTime shr 8).toByte()
            this[3] = (unixTime shr 0).toByte()
        }
    }

    private suspend fun readTLSHeader(): Boolean {
        return input.readTLSHeader(header)
    }

    private suspend fun readPacket(): ByteReadPacket {
        return input.readPacket(header.length)
    }

    private suspend fun changeCipherSpec(flag: Byte) {
        if (!readTLSHeader()) throw TLSException("Handshake failed: premature end of stream")
        if (header.type != RecordType.Handshake) {
            // TODO alert ?
            throw TLSException("Unexpected record of type ${header.type} (${header.length} bytes)")
        }

        val encryptedPacket = readPacket()
        val recordIv = encryptedPacket.readLong()
        val cipher = decryptCipher(cipherSuite!!, keyMaterial, RecordType.Handshake, header.length, recordIv, 0)
        val decrypted = encryptedPacket.decrypted(cipher)

        decrypted.readTLSHandshake(handshakeHeader)
        if (handshakeHeader.type != TLSHandshakeType.Finished) throw TLSException("TLS handshake failed: expected Finihsed record after ChangeCipherSpec but got ${handshakeHeader.type}")

        // TODO verify data!!!
    }

    private suspend fun handshake(packet: ByteReadPacket) {
        when (handshakeHeader.type) {
            TLSHandshakeType.ServerHello -> {
                packet.readTLSServerHello(handshakeHeader)
                serverRandom = handshakeHeader.random.copyOf()
                cipherSuite = cipherSuites[handshakeHeader.suites[0]]
            }
            TLSHandshakeType.Certificate -> {
                val certs = packet.readTLSCertificate(handshakeHeader)
                serverKey = certs.firstOrNull()?.publicKey ?: throw TLSException("No server certificate/public key found")
            }
            TLSHandshakeType.ServerDone -> {
                preSecret = random.generateSeed(48)
                preSecret[0] = 0x03
                preSecret[1] = 0x03 // TLS 1.2

                val (e, copy) = clientKeyExchange(random, handshakeHeader, serverKey!!, preSecret).duplicate()
                packetForHashing.writePacket(copy)

                header.type = RecordType.Handshake
                header.length = e.remaining
                output.writePacket {
                    writeTLSHeader(header)
                }
                output.writePacket(e)

                output.writePacket {
                    writeChangeCipherSpec(header)
                }

                val hash = doHash()
                val suite = cipherSuite!!
                masterSecret = masterSecret(SecretKeySpec(preSecret, suite.macName), clientRandom, serverRandom)
                preSecret.fill(0)
                preSecret = EmptyByteArray

                val finishedBody = finished(hash, masterSecret!!)
                val finished = buildPacket {
                    handshakeHeader.type = TLSHandshakeType.Finished
                    handshakeHeader.length = finishedBody.remaining
                    writeTLSHandshake(handshakeHeader)
                    writePacket(finishedBody)
                }

                keyMaterial = keyMaterial(masterSecret!!, serverRandom + clientRandom, suite.keyStrengthInBytes, suite.macStrengthInBytes, suite.fixedIvLength)
                val cipher = encryptCipher(suite, keyMaterial, RecordType.Handshake, finished.remaining, 0, 0)

                val finishedEncrypted = finished.encrypted(cipher, 0)

                output.writePacket {
                    header.type = RecordType.Handshake
                    header.length = finishedEncrypted.remaining
                    writeTLSHeader(header)
                }
                output.writePacket(finishedEncrypted)

                output.flush()
            }
            else -> throw TLSException("Unsupported TLS handshake type ${handshakeHeader.type}")
        }
    }

    private suspend fun sendClientHello() {
        handshakeHeader.type = TLSHandshakeType.ClientHello
        handshakeHeader.suitesCount = 1
//            handshake.suites[0] = 0x009d
        handshakeHeader.suites[0] = 0x009c
        handshakeHeader.random = clientRandom.copyOf()

        val helloBody = WritePacket()
        helloBody.writeTLSClientHello(handshakeHeader)

        val (hello, copy) = buildPacket {
            handshakeHeader.type = TLSHandshakeType.ClientHello
            handshakeHeader.length = helloBody.size
            writeTLSHandshake(handshakeHeader)
            writePacket(helloBody.build())
        }.duplicate()

        packetForHashing.writePacket(copy)
        output.writePacket {
            header.type = RecordType.Handshake
            header.length = hello.remaining
            writeTLSHeader(header)
            writePacket(hello)
        }
        output.flush()
    }

    private fun doHash(): ByteArray {
        val (p, copy) = packetForHashing.build().duplicate()

        val hs = TLSHandshakeHeader()
        while (copy.remaining > 0) {
            copy.readTLSHandshake(hs)
            println("${hs.type} (${hs.length} bytes")
            copy.skipExact(hs.length)
        }

        val digest = MessageDigest.getInstance(cipherSuite!!.hashName)!!

        val buffer = DefaultByteBufferPool.borrow()
        try {
            while (true) {
                val rc = p.readAvailable(buffer)
                if (rc == -1) break
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }

            return digest.digest()
        } finally {
            DefaultByteBufferPool.recycle(buffer)
        }
    }

    companion object {
        private val EmptyByteArray = ByteArray(0)
    }
}

fun main(args: Array<String>) {
//    val remoteAddress = InetSocketAddress(InetAddress.getByName("ya.ru"), 443)
    val remoteAddress = InetSocketAddress(InetAddress.getByName("localhost"), 44330)
//    val remoteAddress = InetSocketAddress(InetAddress.getByName("localhost"), 9443)

    runBlocking {
        ActorSelectorManager().use { selector ->
            aSocket(selector).tcp().connect(remoteAddress).use { socket ->
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()

                val session = TLSClientSession(input, output)
                launch(CommonPool) {
                    session.run()
                }

                session.appDataOutput.writeStringUtf8("GET / HTTP/1.1\r\nHost: localhost:44330\r\nConnection: keep-alive\r\n\r\n")
                session.appDataOutput.flush()

                val bb = ByteBuffer.allocate(8192)
                while (true) {
                    val rc = session.appDataInput.readAvailable(bb)
                    if (rc == -1) break
                    bb.flip()
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), rc)
                    System.out.flush()
                }
            }
        }
    }
}

private val MASTER_SECRET_LABEL = "master secret".toByteArray()
private val CLIENT_FINISHED_LABEL = "client finished".toByteArray()
private val KEY_EXPANSION_LABEL = "key expansion".toByteArray()

// Cipher Suite: TLS_RSA_WITH_AES_256_GCM_SHA384 (0x009d)
// TLS_RSA_WITH_AES_128_GCM_SHA256 (0x009c)
private fun encryptCipher(suite: CipherSuite, keyMaterial: ByteArray, recordType: RecordType, recordLength: Int, recordIv: Long, seq: Long): Cipher {
    val cipher = Cipher.getInstance(suite.jdkCipherName)

    val key = keyMaterial.clientKey(suite)
    val fixedIv = keyMaterial.clientIV(suite)
    val iv = fixedIv.copyOf(suite.ivLength)

    var s = recordIv
    for (idx in suite.ivLength - 1 downTo suite.fixedIvLength) {
        iv[idx] = (s and 0xff).toByte()
        s = s ushr 8
    }

    // TODO non-gcm ciphers
    val gcmSpec = GCMParameterSpec(suite.cipherTagSizeInBytes * 8, iv)

    cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

    val aad = ByteArray(13)
    s = seq
    for (idx in 7 downTo 0) {
        aad[idx] = (s and 0xff).toByte()
        s = s ushr 8
    }
    aad[9] = 3 // TLS 1.2
    aad[10] = 3

    aad[8] = recordType.code.toByte()
    aad[11] = (recordLength shr 8).toByte()
    aad[12] = (recordLength and 0xff).toByte()

    cipher.updateAAD(aad)

    return cipher
}

private fun decryptCipher(suite: CipherSuite, keyMaterial: ByteArray, recordType: RecordType, recordLength: Int, recordIv: Long, seq: Long): Cipher {
    val cipher = Cipher.getInstance(suite.jdkCipherName)

    val key = keyMaterial.serverKey(suite)
    val fixedIv = keyMaterial.serverIV(suite)
    val iv = fixedIv.copyOf(suite.ivLength)

    var s = recordIv
    for (idx in suite.ivLength - 1 downTo suite.fixedIvLength) {
        iv[idx] = (s and 0xff).toByte()
        s = s ushr 8
    }

    // TODO non-gcm ciphers
    val gcmSpec = GCMParameterSpec(suite.cipherTagSizeInBytes * 8, iv)

    cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

    val contentSize = recordLength - (suite.ivLength - suite.fixedIvLength) - suite.cipherTagSizeInBytes
    val aad = ByteArray(13)
    s = seq
    for (idx in 7 downTo 0) {
        aad[idx] = (s and 0xff).toByte()
        s = s ushr 8
    }

    aad[9] = 3 // TLS 1.2
    aad[10] = 3

    aad[8] = recordType.code.toByte()
    aad[11] = (contentSize shr 8).toByte()
    aad[12] = (contentSize and 0xff).toByte()

    cipher.updateAAD(aad)

    return cipher
}


internal fun ByteArray.clientKey(suite: CipherSuite) = SecretKeySpec(this, 2 * suite.macStrengthInBytes, suite.keyStrengthInBytes, suite.jdkCipherName.substringBefore("/"))
internal fun ByteArray.serverKey(suite: CipherSuite) = SecretKeySpec(this, 2 * suite.macStrengthInBytes + suite.keyStrengthInBytes, suite.keyStrengthInBytes, suite.jdkCipherName.substringBefore("/"))
internal fun ByteArray.clientIV(suite: CipherSuite) = this.copyOfRange(2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes, 2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + suite.fixedIvLength)
internal fun ByteArray.serverIV(suite: CipherSuite) = this.copyOfRange(2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + suite.fixedIvLength, 2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + 2 * suite.fixedIvLength)

internal fun keyMaterial(masterSecret: SecretKey, seed: ByteArray, keySize: Int, macSize: Int, ivSize: Int): ByteArray {
    val materialSize = 2 * macSize + 2 * keySize + 2 * ivSize
    return PRF(masterSecret, KEY_EXPANSION_LABEL, seed, materialSize)
}

internal fun masterSecret(preMasterSecret: SecretKey, clientRandom: ByteArray, serverRandom: ByteArray): SecretKeySpec {
    return PRF(preMasterSecret, MASTER_SECRET_LABEL, clientRandom + serverRandom, 48).let { SecretKeySpec(it, preMasterSecret.algorithm) }
}

private fun PRF(secret: SecretKey, label: ByteArray, seed: ByteArray, requiredLength: Int = 12) = P_hash(label + seed, Mac.getInstance(secret.algorithm), secret, requiredLength)

private fun P_hash(seed: ByteArray, mac: Mac, secretKey: SecretKey, requiredLength: Int = 12): ByteArray {
    require(requiredLength >= 12)

    var A = seed
    var result = ByteArray(0)

    while (result.size < requiredLength) {
        mac.reset()
        mac.init(secretKey)
        mac.update(A)
        A = mac.doFinal()

        mac.reset()
        mac.init(secretKey)
        mac.update(A)
        mac.update(seed)

        result += mac.doFinal()
    }

    return result.copyOf(requiredLength)
}

fun digest(d: MessageDigest, coroutineContext: CoroutineContext, result: ByteArray): ReaderJob {
    return reader(coroutineContext) {
        d.reset()
        val buffer = DefaultByteBufferPool.borrow()
        try {
            while (true) {
                buffer.clear()
                val rc = channel.readAvailable(buffer)
                if (rc == -1) break
                buffer.flip()
                d.update(buffer)
            }

            d.digest(result, 0, d.digestLength)
        } finally {
            DefaultByteBufferPool.recycle(buffer)
        }
    }
}

private fun ByteReadPacket.copyTo(dst: ByteWritePacket) {
    val buffer = DefaultByteBufferPool.borrow()
    try {
        while (true) {
            buffer.clear()
            val rc = readAvailable(buffer)
            if (rc == -1) break
            buffer.flip()
            dst.writeFully(buffer)
        }
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}

private fun ByteWritePacket.writePacket(p: ByteReadPacket) {
    p.copyTo(this)
}


private fun ByteReadPacket.duplicate(): Pair<ByteReadPacket, ByteReadPacket> {
    val buffer = DefaultByteBufferPool.borrow()
    val p1 = WritePacket()
    val p2 = WritePacket()

    try {
        while (true) {
            buffer.clear()
            val rc = readAvailable(buffer)
            if (rc == -1) break
            buffer.flip()
            buffer.mark()
            p1.writeFully(buffer)
            buffer.reset()
            p2.writeFully(buffer)
        }

        return Pair(p1.build(), p2.build())
    } catch (t: Throwable) {
        p1.release()
        p2.release()
        throw t
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}

private fun ByteReadPacket.encrypted(cipher: Cipher, recordIv: Long): ByteReadPacket {
    val buffer = DefaultByteBufferPool.borrow()
    val encrypted = DefaultByteBufferPool.borrow()
    try {
        return buildPacket {
            buffer.clear()

            writeLong(recordIv)

            while (true) {
                val rc = if (buffer.hasRemaining()) readAvailable(buffer) else 0
                if (rc == -1) break
                buffer.flip()

                encrypted.clear()
                cipher.update(buffer, encrypted)
                encrypted.flip()
                writeFully(encrypted)
                buffer.compact()
            }

            writeFully(cipher.doFinal()) // TODO use encrypted buffer instead
        }
    } finally {
        DefaultByteBufferPool.recycle(buffer)
        DefaultByteBufferPool.recycle(encrypted)
    }
}

private fun ByteReadPacket.decrypted(cipher: Cipher): ByteReadPacket {
    val buffer = DefaultByteBufferPool.borrow()
    val decrypted = DefaultByteBufferPool.borrow()
    try {
        return buildPacket {
            buffer.clear()

            while (true) {
                val rc = if (buffer.hasRemaining()) readAvailable(buffer) else 0
                if (rc == -1) break
                buffer.flip()

                decrypted.clear()
                cipher.update(buffer, decrypted)
                decrypted.flip()
                writeFully(decrypted)
                buffer.compact()
            }

            writeFully(cipher.doFinal()) // TODO use decrypted buffer instead
        }
    } finally {
        DefaultByteBufferPool.recycle(buffer)
        DefaultByteBufferPool.recycle(decrypted)
    }
}