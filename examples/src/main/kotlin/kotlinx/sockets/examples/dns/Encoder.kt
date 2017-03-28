package kotlinx.sockets.examples.dns

import kotlinx.sockets.channels.*
import java.nio.charset.*

suspend fun BufferedWriteChannel.write(message: Message, encoder: CharsetEncoder, tcp: Boolean = true) {
    ensureCapacity(12 + if (tcp) 2 else 0)

    if (tcp) {
        putUShort(12 + message.questions.sumBy { it.measure() }
                + message.answers.sumBy { it.measure() }
                + message.nameServers.sumBy { it.measure() }
                + message.additional.sumBy { it.measure() }
        )
    }

    putShort(message.header.id)
    putUByte(
            (bit(7, !message.header.isQuery) or
                    bits(4, 4, message.header.opcode.value) or
                    bit(3, message.header.authoritativeAnswer) or
                    bit(1, message.header.truncation) or
                    bit(0, message.header.recursionDesired))
    )
    putUByte(bits(4, 0, message.header.responseCode.value)
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

private suspend fun BufferedWriteChannel.writeResource(resource: Resource<*>, encoder: CharsetEncoder) {
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

private suspend fun BufferedWriteChannel.encodeStringsSequence(items: Iterable<String>, encoder: CharsetEncoder) {
    for (s in items) {
        ensureCapacity(1)
        putUByte(s.length)
        putString(s, encoder)
    }
    ensureCapacity(1)
    putUByte(0)
}


private fun bit(shift: Int, value: Boolean): Int {
    return bits(1, shift, if (value) 1 else 0)
}

private fun bits(size: Int, shift: Int, value: Int): Int {
    val mask = (1..size).fold(0) { acc, _ -> (acc shl 1) or 1 }
    return (value and mask) shl shift
}

