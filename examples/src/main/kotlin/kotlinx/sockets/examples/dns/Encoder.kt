package kotlinx.sockets.examples.dns

import kotlinx.coroutines.experimental.io.*
import java.nio.charset.*

suspend fun ByteWriteChannel.write(message: Message, encoder: CharsetEncoder, tcp: Boolean = true) {
    if (tcp) {
        writeShort(12 + message.questions.sumBy { it.measure() }
                + message.answers.sumBy { it.measure() }
                + message.nameServers.sumBy { it.measure() }
                + message.additional.sumBy { it.measure() }
        )
    }

    writeShort(message.header.id)
    writeByte(
            (bit(7, !message.header.isQuery) or
                    bits(4, 4, message.header.opcode.value) or
                    bit(3, message.header.authoritativeAnswer) or
                    bit(1, message.header.truncation) or
                    bit(0, message.header.recursionDesired))
    )
    writeByte(bits(4, 0, message.header.responseCode.value)
            or bit(7, message.header.recursionAvailable)
            or bit(5, message.header.authenticData)
            or bit(4, message.header.checkingDisabled)
    )

    writeShort(message.header.questionsCount)
    writeShort(message.header.answersCount)
    writeShort(message.header.nameServersCount)
    writeShort(message.header.additionalResourcesCount)

    message.questions.forEach { q ->
        encodeStringsSequence(q.name)

        writeShort(q.type.value)
        writeShort(q.qclass.value)
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

private suspend fun ByteWriteChannel.writeResource(resource: Resource<*>, encoder: CharsetEncoder) {
    encodeStringsSequence(resource.name)
    
    writeShort(resource.type.value)

    when (resource) {
        is Resource.Opt -> {
            writeShort(resource.udpPayloadSize)

            writeByte(resource.extendedRCode)
            writeByte(resource.version)
            writeShort(0) // D0 bit = 0
        }
        else -> throw IllegalArgumentException("resource of type ${resource.type} is not supported")
    }

    if (resource.length != 0) {
        TODO()
    }

    writeShort(resource.length)
}

private fun Question.measure(): Int {
    return 4 + name.sumBy { 1 + it.length } + 1
}

private fun Resource<*>.measure(): Int {
    return name.sumBy { 1 + it.length } + 1 + 10 + length
}

private suspend fun ByteWriteChannel.encodeStringsSequence(items: Iterable<String>) {
    for (s in items) {
        writeByte(s.length)
        writeStringUtf8(s)
    }

    writeByte(0)
}


private fun bit(shift: Int, value: Boolean): Int {
    return bits(1, shift, if (value) 1 else 0)
}

private fun bits(size: Int, shift: Int, value: Int): Int {
    val mask = (1..size).fold(0) { acc, _ -> (acc shl 1) or 1 }
    return (value and mask) shl shift
}

