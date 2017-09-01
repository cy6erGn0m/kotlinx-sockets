package kotlinx.http.impl

import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.packet.*

class RequestResponseBuilder {
    private val packet = WritePacket()

    fun responseLine(version: CharSequence, status: Int, statusText: CharSequence) {
        packet.writeStringUtf8(version)
        packet.writeByte(SP)
        packet.writeStringUtf8(status.toString())
        packet.writeByte(SP)
        packet.writeStringUtf8(statusText)
        packet.writeByte(CR)
        packet.writeByte(LF)
    }

    fun requestLine(method: HttpMethod, uri: CharSequence, version: CharSequence) {
        packet.writeStringUtf8(method.name)
        packet.writeByte(SP)
        packet.writeStringUtf8(uri)
        packet.writeByte(SP)
        packet.writeStringUtf8(version)
        packet.writeByte(CR)
        packet.writeByte(LF)
    }

    fun line(line: CharSequence) {
        packet.append(line)
        packet.writeByte(CR)
        packet.writeByte(LF)
    }

    fun headerLine(name: CharSequence, value: CharSequence) {
        packet.append(name)
        packet.append(": ")
        packet.append(value)
        packet.writeByte(CR)
        packet.writeByte(LF)
    }

    fun emptyLine() {
        packet.writeByte(CR)
        packet.writeByte(LF)
    }

    suspend fun writeTo(out: ByteWriteChannel) {
        out.writePacket(packet.build())
    }

    fun release() {
        packet.release()
    }
}

private const val SP: Byte = 0x20
private const val CR: Byte = 0x0d
private const val LF: Byte = 0x0a