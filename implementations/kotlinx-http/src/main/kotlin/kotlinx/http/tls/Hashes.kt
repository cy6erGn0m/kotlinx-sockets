package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*
import kotlinx.sockets.*
import java.security.*
import javax.crypto.*
import kotlin.coroutines.experimental.*


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

internal fun PRF(secret: SecretKey, label: ByteArray, seed: ByteArray, requiredLength: Int = 12) = P_hash(label + seed, Mac.getInstance(secret.algorithm), secret, requiredLength)

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
