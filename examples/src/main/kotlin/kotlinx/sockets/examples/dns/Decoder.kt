package kotlinx.sockets.examples.dns

import kotlinx.sockets.*
import kotlinx.sockets.channels.*
import java.net.*
import java.nio.*
import java.nio.charset.*

private class DomainNameCompressionSupport {
    val stringMap = HashMap<Int, List<String>>()
    var currentOffset = 12
}

suspend fun BufferedReadChannel.readMessage(tcp: Boolean): Message {
    fill(2)

    if (tcp) {
        getUShort() // TODO process parts
    }

    val support = DomainNameCompressionSupport()
    val decoder = Charsets.ISO_8859_1.newDecoder()
    val header = readHeader()

    return Message(header,
            (1..header.questionsCount).mapNotNull { readQuestion(decoder, support) },
            (1..header.answersCount).mapNotNull { readResource(decoder, support) },
            (1..header.nameServersCount).mapNotNull { readResource(decoder, support) },
            (1..header.additionalResourcesCount).mapNotNull { readResource(decoder, support) }
    )
}

private suspend fun BufferedReadChannel.readHeader(): Header {
    fill(12)

    val id = getShort()
    val flags1 = getByte()
    val flags2 = getByte()

    val questionsCount = getUShort()
    val answersCount = getUShort()
    val nameServersCount = getUShort()
    val additionalCount = getUShort()

    val opcode = Opcode.byValue[(flags1.toInt() and 0xff) shr 3 and 0xf] ?: throw IllegalArgumentException("Wrong opcode")
    val responseCode = ResponseCode.byValue[flags2.toInt() and 0xf] ?: throw IllegalArgumentException("Wrong response code")

    return Header(id,
            isQuery = !flags1.flag(7),
            opcode = opcode,
            authoritativeAnswer = flags1.flag(2),
            truncation = flags1.flag(1),
            recursionDesired = flags1.flag(0),
            recursionAvailable = flags2.flag(7),
            authenticData = flags2.flag(5),
            checkingDisabled = flags2.flag(4),
            responseCode = responseCode,
            questionsCount = questionsCount,
            answersCount = answersCount,
            nameServersCount = nameServersCount,
            additionalResourcesCount = additionalCount
    )
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Byte.flag(n: Int): Boolean = ((toInt() and 0xff) and (1 shl n)) != 0

private suspend fun BufferedReadChannel.readQuestion(decoder: CharsetDecoder, support: DomainNameCompressionSupport): Question? {
    val name = readName(decoder, support)

    fill(4)
    val typeValue = getUShort()
    val type = Type.byValue[typeValue]

    if (type == null) {
        System.err.println("Wrong question record type $typeValue")
        getUShort()
        return null
    }

    val classValue = getUShort()
    val qclass = Class.byValue[classValue]

    if (qclass == null) {
        System.err.println("Wrong question record class $classValue")
        return null
    }

    support.currentOffset += 4

    return Question(name, type, qclass)
}

private suspend fun BufferedReadChannel.readResource(decoder: CharsetDecoder, support: DomainNameCompressionSupport): Resource<*>? {
    val name = readName(decoder, support)
    fill(10)

    val typeValue = getUShort()
    val value1 = getUShort()
    val value2 = getUInt()
    val length = getUShort()

    support.currentOffset += 10

    val type = Type.byValue[typeValue]
//    println("Got $type")
    val result = when (type) {
        Type.A -> {
            val qClass = Class.byValue[value1]

            if (length == 4 && qClass != null) {
                val buffer = ByteBuffer.allocate(4)
                readFully(buffer)
                support.currentOffset += 4
                Resource.A.V4(name, qClass, InetAddress.getByAddress(buffer.array()) as Inet4Address, ttl = value2)
            } else null
        }
        Type.AAAA -> {
            val qClass = Class.byValue[value1]

            if (length == 16 && qClass != null) {
                val buffer = ByteBuffer.allocate(16)
                readFully(buffer)
                support.currentOffset += 4
                Resource.A.V6(name, qClass, InetAddress.getByAddress(buffer.array()) as Inet6Address, ttl = value2)
            } else null
        }
        Type.NS -> {
            val qClass = Class.byValue[value1]
            if (length == 0 || qClass == null) {
                null
            } else {
                val nameServer = readName(decoder, support)
                return Resource.Ns(name, qClass, nameServer, ttl = value2)
            }
        }
        Type.OPT -> {
            if (name.isNotEmpty()) {
                System.err.println("OPT record should have root name")
            }

            if (length > 0) {
                skipExact(length)
                support.currentOffset += length
            }

            // here wee are just to eliminate warning, we actually don't support EDNS0
            return Resource.Opt(name, value1, ((value2 shr 24) and 0xffL).toByte(), ((value2 shr 16) and 0xffL).toByte())
        }
        Type.SOA -> {
            val mname = readName(decoder, support)
            val rname = readName(decoder, support)

            fill(5 * 4)
            val serial = getUInt()
            val refresh = getUInt()
            val retry = getUInt()
            val expire = getUInt()
            val minimum = getUInt()

            support.currentOffset += 20

            return Resource.SOA(name, mname, rname, serial, refresh, retry, expire, minimum)
        }
        Type.CNAME -> {
            val cname = readName(decoder, support)
            return Resource.CName(name, cname, ttl = value2)
        }
        Type.TXT -> {
            var remaining = length
            val textBuffer = pool.receive()
            val texts = ArrayList<String>(2)

            while (remaining > 0) {
                fill(1)
                val textSize = getUByte()

                textBuffer.clear()
                textBuffer.limit(minOf(textSize, remaining))

                readFully(textBuffer)
                textBuffer.flip()

                val text = decoder.decode(textBuffer).toString()
                texts += text
                support.currentOffset += textSize + 1
                remaining -= textSize + 1
            }

            pool.offer(textBuffer)

            return Resource.Text(name, texts, length)
        }
        Type.MX -> {
            fill(3)
            val preference = getUShort()
            support.currentOffset += 2
            val exchange = readName(decoder, support)

            return Resource.MX(name, preference, exchange)
        }
        Type.SPF -> null
        else -> null // TODO more types
    }

    if (result == null) {
        System.err.println("Unable to parse record of type $typeValue (${type ?: "Unknown type"}), index ${support.currentOffset}")
        skipExact(length)
        support.currentOffset += length
    }

    return result
}

private suspend fun BufferedReadChannel.readName(decoder: CharsetDecoder, support: DomainNameCompressionSupport): List<String> {
    val initialOffset = support.currentOffset
    var currentOffset = initialOffset
    val name = ArrayList<String>()

    do {
        fill(1)
        val partLength = getUByte()
        if (partLength == 0) {
            currentOffset++
            break
        } else if (partLength and 0xc0 == 0xc0) { // two higher bits are 11 so use compressed
            fill(1)
            val lower = getUByte()
            val offset = lower or ((partLength and 0x3f) shl 8)

            if (offset >= currentOffset) {
                throw IllegalArgumentException("Forward references are not supported") // TODO in theory compressed message could have forward references
            }

            val referred = support.stringMap[offset] ?: run {
                throw IllegalArgumentException("Illegal offset $offset for compressed domain name, known offsets are ${support.stringMap.keys}, name start $initialOffset")
            }

            updateSupport(support, initialOffset, name, referred)
            currentOffset += 2
            support.currentOffset = currentOffset
            return name + referred
        }

        name += getStringByRawLength(partLength, decoder)
        currentOffset += partLength + 1
    } while (true)

    updateSupport(support, initialOffset, name, emptyList())
    support.currentOffset = currentOffset

    return name
}

private fun updateSupport(support: DomainNameCompressionSupport, initialOffset: Int, before: List<String>, after: List<String>) {
    for (idx in 0 .. before.size - 1) {
        val index = initialOffset + before.subList(0, idx).sumBy { it.length + 1 }
        val value = when {
            idx == 0 && after.isEmpty() -> before
            else -> before.subList(idx, before.size) + after
        }

        support.stringMap[index] = value
    }
}

