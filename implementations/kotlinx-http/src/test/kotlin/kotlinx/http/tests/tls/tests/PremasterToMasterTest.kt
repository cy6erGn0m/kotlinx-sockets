package kotlinx.http.tests.tls.tests

import kotlinx.http.tls.*
import org.junit.*
import sun.security.internal.spec.*
import java.io.*
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.test.*

class PremasterToMasterTest {
    @Suppress("DEPRECATION")
    @Test
    fun smokeTest() {
        val clientRandom = decodeHexStream("5cf01e425db25febe563402f4fa8eb14b6354e7826fc9b8dcad9f7f22606b030")
        val serverRandom = decodeHexStream("d2497019097665958f7836dffd185cd88435a64156ab849231cbe4cf18012295")

        val premasterKG = KeyGenerator.getInstance("SunTls12RsaPremasterSecret")
        premasterKG.init(TlsRsaPremasterSecretParameterSpec(0x0303, 0x0303))
        val premasterKey = premasterKG.generateKey()

        val master = masterSecret(SecretKeySpec(premasterKey.encoded, "HmacSHA256"), clientRandom, serverRandom)
        val masterKeyBytes = master.encoded

        val masterKG = KeyGenerator.getInstance("SunTls12MasterSecret")
        masterKG.init(TlsMasterSecretParameterSpec(premasterKey, 0x03, 0x03, clientRandom, serverRandom, "SHA-256", 32, 64))
        val masterKey = masterKG.generateKey()
        val masterKeyBytes2 = masterKey.encoded

        assertTrue { masterKeyBytes2.contentEquals(masterKeyBytes) }

        val maGen = KeyGenerator.getInstance("SunTls12KeyMaterial")
        maGen.init(TlsKeyMaterialParameterSpec(masterKey, 0x03, 0x03, clientRandom, serverRandom, "AES", TLS_RSA_WITH_AES_128_GCM_SHA256.keyStrengthInBytes, TLS_RSA_WITH_AES_128_GCM_SHA256.macStrengthInBytes, TLS_RSA_WITH_AES_128_GCM_SHA256.fixedIvLength, TLS_RSA_WITH_AES_128_GCM_SHA256.macStrengthInBytes, "SHA-256", 32, 64))
        val material = maGen.generateKey() as TlsKeyMaterialSpec
        val myMaterial = keyMaterial(master, serverRandom + clientRandom, TLS_RSA_WITH_AES_128_GCM_SHA256.keyStrengthInBytes, TLS_RSA_WITH_AES_128_GCM_SHA256.macStrengthInBytes, TLS_RSA_WITH_AES_128_GCM_SHA256.fixedIvLength)
        val myClientKey = myMaterial.clientKey(TLS_RSA_WITH_AES_128_GCM_SHA256)
        val myClientIv = myMaterial.clientIV(TLS_RSA_WITH_AES_128_GCM_SHA256)

        assertTrue { myClientKey.encoded.contentEquals(material.clientCipherKey.encoded) }
        assertTrue { myClientIv.contentEquals(material.clientIv.iv) }
    }

    private fun decodeHexDump(dump: String): ByteArray {
        val baos = ByteArrayOutputStream()

        dump.lines().forEach {
            val bytes = it.trim().split("\\s+".toRegex()).drop(1).map { it.toInt(16).toByte() }.toByteArray()
            baos.write(bytes)
        }

        return baos.toByteArray()
    }

    private fun decodeHexStream(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)

        var rem = hex
        var idx = 0
        while (rem.isNotEmpty()) {
            val c = rem.take(2)
            rem = rem.drop(2)
            result[idx++] = c.toInt(16).toByte()
        }

        return result
    }

}