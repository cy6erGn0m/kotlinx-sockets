package kotlinx.sockets.examples.dns

import kotlinx.sockets.*
import java.net.*
import java.nio.*
import java.nio.charset.*

suspend fun BinaryWriteChannel.write(message: Message, encoder: CharsetEncoder, tcp: Boolean = true) {
    ensureCapacity(12 + if (tcp) 2 else 0)

    if (tcp) {
        putUShort(12 + message.questions.sumBy { it.measure() }
                + message.answers.sumBy { it.measure() }
                + message.nameServers.sumBy { it.measure() }
                + message.additional.sumBy { it.measure() }
        )
    }

    putShort(message.header.id)
    putByteInt(
            (bit(7, !message.header.isQuery) or
                    bits(4, 4, message.header.opcode.value) or
                    bit(3, message.header.authoritativeAnswer) or
                    bit(1, message.header.truncation) or
                    bit(0, message.header.recursionDesired))
    )
    putByteInt(bits(4, 0, message.header.responseCode.value)
            or bit(7, message.header.recursionAvailable)
            or bit(5, message.header.authenticData)
            or bit(4, message.header.checkingDisabled)
    )

    putUShort(message.header.questionsCount)
    putUShort(message.header.answersCount)
    putUShort(message.header.nameServersCount)
    putUShort(message.header.additionalResourcesCount)

    message.questions.forEach { q ->
        encodeStringsSequence(q.name, encoder)
        ensureCapacity(4)
        putUShort(q.type.value)
        putUShort(q.qclass.value)
    }

    message.answers.forEach { a ->
        writeResource(a, encoder)
    }

    message.nameServers.forEach { a ->
        writeResource(a, encoder)
    }

    message.additional.forEach { a ->
        writeResource(a, encoder)
    }

    // TODO resources, name servers and additional resources
}

private suspend fun BinaryWriteChannel.writeResource(resource: Resource<*>, encoder: CharsetEncoder) {
    encodeStringsSequence(resource.name, encoder)
    ensureCapacity(10)
    putUShort(resource.type.value)

    when (resource) {
        is Resource.Opt -> {
            putUShort(resource.udpPayloadSize)

            putByte(resource.extendedRCode)
            putByte(resource.version)
            putShort(0) // D0 bit = 0
        }
        else -> throw IllegalArgumentException("resource of type ${resource.type} is not supported")
    }

    if (resource.length != 0) {
        TODO()
    }

    putUShort(resource.length)
}

private fun Question.measure(): Int {
    return 4 + name.sumBy { 1 + it.length } + 1
}

private fun Resource<*>.measure(): Int {
    return name.sumBy { 1 + it.length } + 1 + 10 + length
}

private class DomainNameCompressionSupport {
    val stringMap = HashMap<Int, List<String>>()
    var currentOffset = 12
}

suspend fun BinaryReadChannel.readMessage(tcp: Boolean): Message {
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

private suspend fun BinaryReadChannel.readHeader(): Header {
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

private suspend fun BinaryReadChannel.readQuestion(decoder: CharsetDecoder, support: DomainNameCompressionSupport): Question? {
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

private suspend fun BinaryReadChannel.readResource(decoder: CharsetDecoder, support: DomainNameCompressionSupport): Resource<*>? {
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
        else -> null // TODO more types
    }

    if (result == null) {
        System.err.println("Unable to parse record of type $typeValue (${type ?: "Unknown type"}), index ${support.currentOffset}")
        skipExact(length)
        support.currentOffset += length
    }

    return result
}

private suspend fun BinaryReadChannel.readName(decoder: CharsetDecoder, support: DomainNameCompressionSupport): List<String> {
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

private suspend fun BinaryWriteChannel.encodeStringsSequence(items: Iterable<String>, encoder: CharsetEncoder) {
    for (s in items) {
        ensureCapacity(1)
        putByteInt(s.length)
        putString(s, encoder)
    }
    ensureCapacity(1)
    putByteInt(0)
}


private fun bit(shift: Int, value: Boolean): Int {
    return bits(1, shift, if (value) 1 else 0)
}

private fun bits(size: Int, shift: Int, value: Int): Int {
    val mask = (1..size).fold(0) { acc, _ -> (acc shl 1) or 1 }
    return (value and mask) shl shift
}

