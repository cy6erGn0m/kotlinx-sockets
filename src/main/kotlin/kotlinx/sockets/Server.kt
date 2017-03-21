package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.net.*
import java.nio.*
import java.util.concurrent.*

private val bufferSize = 8192
private val buffersCount = 100000
private val bufferPool = ArrayBlockingQueue<ByteBuffer>(buffersCount)

fun main(args: Array<String>) {
    for (i in 1..buffersCount) {
        bufferPool.put(ByteBuffer.allocate(bufferSize))
    }

    runBlocking {
        val dispatcher = ioPool.asCoroutineDispatcher()

        SelectorManager().use { s ->
            s.serverSocket().use { server ->
                server.bind(InetSocketAddress(9094))

                while (true) {
                    try {
                        val client = server.accept()
                        launch(dispatcher) {
                            client.use {
                                handleClient(client)
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}

private suspend fun handleClient(client: AsyncSocket) {
    val bb = bufferPool.poll() ?: ByteBuffer.allocate(bufferSize)

    try {
        loop@ while (true) {
            val parser = Parser(bb)
            val request = parser.parse(client) ?: break@loop

            when {
                request.method != "GET" -> {
                    client.respond(405, "Method Not Allowed", request.version, "close", "Not allowed: ${request.method}")
                    break@loop
                }
                request.uri != "/" -> {
                    val connection = request.header("Connection").singleOrNull()?.value(request.headersBody) ?: defaultConnectionForVersion(request.version)

                    client.respond(404, "Not Found", request.version, connection, "Not found: ${request.uri}")

                    if (connection.equals("close", ignoreCase = true)) {
                        break@loop
                    }
                }
                else -> {
                    val connection = request.header("Connection").singleOrNull()?.value(request.headersBody) ?: defaultConnectionForVersion(request.version)

                    client.respond(200, "OK", request.version, connection, "Hello, World!")

                    if (connection.equals("close", ignoreCase = true)) {
                        break@loop
                    }
                }
            }
        }
    } finally {
        bufferPool.offer(bb)
    }
}

private fun defaultConnectionForVersion(version: String) = if (version == "HTTP/1.1") "keep-alive" else "close"

private suspend fun WriteChannel.respond(code: Int, statusMessage: String, version: String, connection: String, content: String) {
    write(ByteBuffer.wrap(buildString(256) {
        append(version)
        append(' ')
        append(code)
        append(' ')
        append(statusMessage)
        append("\r\n")

        append("Connection: "); append(connection); append("\r\n")
        append("Content-Type: text/plain\r\n")
        append("Content-Length: "); append((content.length + 2).toString()); append("\r\n")
        append("\r\n")
        append(content)
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)))
}

private class HeaderEntry(val nameStart: Int, val nameLength: Int, val nameHash: Int, val valueStart: Int, val valueLength: Int) {
    private var _name: String? = null
    private var _value: String? = null

    fun name(array: ByteArray): String = _name ?: array.stringOf(nameStart, nameLength).also { _name = it }
    fun value(array: ByteArray): String = _value ?: array.stringOf(valueStart, valueLength).also { _value = it }
}

private class HttpRequest(val method: String, val uri: String, val version: String, val headersBody: ByteArray, val headers: ArrayList<HeaderEntry>) {
    fun header(name: String): List<HeaderEntry> {
        val h = name.hashCodeLowerCase()

        return headers.filter { it.nameHash == h && it.name(headersBody).equals(name, ignoreCase = true) }
    }
}

private class Parser(val bb: ByteBuffer) {
    private var state = State.Method
    private val headersBody: ByteArrayBuilder = ByteArrayBuilder()
    private val headers = ArrayList<HeaderEntry>()

    private var expectedBody = true
    private var method: String? = null
    private var uri: String? = null
    private var version: String? = null

    suspend fun parse(ch: ReadChannel): HttpRequest? {
        readLoop@ while (true) {
            val rc = ch.read(bb)
            if (rc == -1) {
                if (state == State.Method) return null
                else throw ParserException("Unexpected EOF")
            }

            bb.flip()

            parseLoop@ while (true) {
                val result = when (state) {
                    State.Method -> parseMethod()
                    State.Uri -> parseUri()
                    State.Version -> parseVersion()
                    State.Headers -> parseHeader()
                    State.Body -> {
                        if (expectedBody) TODO()

                        bb.compact()
                        break@readLoop
                    }
                }

                if (!result) {
                    break@parseLoop
                }
            }

            bb.compact()
        }

//        println("Parsed.")
//        println(" - method: |$method|")
//        println(" - uri: |$uri|")
//        println(" - version: |$version|")
//        println(" - headers: ${headers.size} pcs")
//        headers.forEach { h ->
//            println("     - |${h.name(headersBody.array)}| = |${h.value(headersBody.array)}|")
//        }
//        println("end")

        return HttpRequest(method!!, uri!!, version!!, headersBody.array, headers)
    }

    private fun parseMethod(): Boolean {
        if (selectUntilSpace(EOL.ShouldNotBe) { start, length -> method = bb.stringOf(start, length) }) {
            expectedBody = when (method?.toUpperCase()) {
                "GET", "HEAD", "OPTIONS" -> false
                else -> true
            }
            state = State.Uri
            return true
        }

        return false
    }

    private fun parseUri(): Boolean {
        if (selectUntilSpace(EOL.ShouldNotBe) { start, length -> uri = bb.stringOf(start, length) }) {
            state = State.Version
            return true
        }

        return false
    }

    private fun parseVersion(): Boolean {
        if (selectUntilSpace(EOL.ShouldBe) { start, length -> version = bb.stringOf(start, length) }) {
            when (version) {
                "HTTP/1.0" -> state = State.Body
                "HTTP/1.1" -> state = State.Headers
                else -> throw ParserException("Unsupported HTTP version |$version|")
            }
            return true
        }

        return false
    }

    private fun parseHeader(): Boolean {
        return selectUntilEol { start, length ->
            if (length == 0) {
                state = State.Body
                return@selectUntilEol
            }

            var nameStart: Int = start
            var nameLength: Int

            val colon = selectCharacter(start) { it == ':' }
            if (colon == -1) throw ParserException("Header should have colon, but got line |${bb.stringOf(colon, length)}|")

            nameLength = colon - start

            while (nameLength > 0 && bb.get(nameStart + nameLength - 1).isWhitespace()) {
                nameLength--
            }

            var valueStart = colon + 1
            var valueLength = length - (colon - start) - 1

            while (valueLength > 0 && bb.get(valueStart).isWhitespace()) {
                valueStart++
                valueLength--
            }

            val hash = bb.hashCodeOf(nameStart, nameLength)
            nameStart = headersBody.append(bb, nameStart, nameLength)
            valueStart = headersBody.append(bb, valueStart, valueLength)

            headers.add(HeaderEntry(nameStart, nameLength, hash, valueStart, valueLength))
        }
    }

    private inline fun selectUntilSpace(eol: EOL, block: (Int, Int) -> Unit): Boolean {
        val end = selectCharacter(predicate = Char::isWhitespace)
        if (end == -1) return false
        val start = bb.position()

        when (eol) {
            EOL.ShouldBe -> if (!bb.get(end).isCrOrLf()) throw ParserException("Expected EOL but got extra characters in line |${bb.stringOf(bb.position(), end - start)}|")
            EOL.ShouldNotBe -> if (bb.get(end).isCrOrLf()) throw ParserException("EOL is unexpected")
            EOL.CouldBe -> Unit
        }

        block(start, end - start)

        bb.position(skipSpacesAndCrLf(end))
        return true
    }

    private inline fun selectUntilEol(block: (Int, Int) -> Unit): Boolean {
        val eol = select { it.isCrOrLf() }
        if (eol == -1) return false

        val start = bb.position()
        block(start, eol - start)

        bb.position(skipCrLf(eol))
        return true
    }

    private inline fun select(start: Int = bb.position(), predicate: (Byte) -> Boolean): Int {
        for (idx in start..bb.limit() - 1) {
            if (predicate(bb.get(idx))) return idx
        }

        return -1
    }

    private inline fun selectCharacter(start: Int = bb.position(), predicate: (Char) -> Boolean): Int = select(start) { predicate(it.toChar()) }

    private fun skipSpacesAndCrLf(pos: Int): Int {
        var newPosition = pos

        while (newPosition < bb.limit() && bb.get(newPosition).let { it.isWhitespace() && !it.isCrOrLf() })
            newPosition++

        return skipCrLf(newPosition)
    }

    private fun skipCrLf(pos: Int): Int {
        if (pos >= bb.limit()) return pos

        var newPosition = pos

        val first = bb.get(newPosition)
        if (first == CR) {
            newPosition++
            if (newPosition < bb.limit() && bb.get(newPosition) == LF) {
                newPosition++
            }
        } else if (first == LF) {
            newPosition++
        }

        return newPosition
    }

    private fun Byte.isCrOrLf() = this == CR || this == LF
    private fun Byte.isWhitespace() = Character.isWhitespace(this.toChar())

    enum class State {
        Method,
        Uri,
        Version,
        Headers,
        Body
    }

    enum class EOL {
        ShouldBe,
        CouldBe,
        ShouldNotBe
    }

    class ParserException(message: String) : Exception(message)

    companion object {
        private const val CR: Byte = 0x0d
        private const val LF: Byte = 0x0a
    }
}

private fun ByteArray.stringOf(start: Int, length: Int): String {
    return String(this, start, length, Charsets.ISO_8859_1)
}

private fun ByteBuffer.stringOf(start: Int, length: Int): String {
    require(hasArray())

    return String(array(), arrayOffset() + start, length, Charsets.ISO_8859_1)
}

private fun String.hashCodeLowerCase(): Int {
    var hashCode = 0
    for (pos in 0..length - 1) {
        var v = get(pos).toInt()
        val vc = v.toChar()

        if (vc in 'A'..'Z')
            v = 'a'.toInt() + (v - 'A'.toInt())

        hashCode = 31 * hashCode + v
    }

    return hashCode
}

private fun ByteBuffer.hashCodeOf(start: Int, length: Int): Int {
    var hashCode = 0
    for (pos in start..start + length - 1) {
        var v = get(pos).toInt()
        val vc = v.toChar()

        if (vc in 'A'..'Z')
            v = 'a'.toInt() + (v - 'A'.toInt())

        hashCode = 31 * hashCode + v
    }

    return hashCode
}

private class ByteArrayBuilder(estimate: Int = 128) {
    private var size = 0
    var array: ByteArray = ByteArray(maxOf(estimate, 16))
        private set

    fun append(source: ByteArray, start: Int, length: Int): Int {
        require(length >= 0)
        ensureCapacity(length)

        val index = size
        System.arraycopy(source, start, array, index, length)
        size += length

        return index
    }

    fun append(source: ByteBuffer, start: Int, length: Int): Int {
        require(length >= 0)
        ensureCapacity(length + 1)

        if (source.hasArray()) {
            val index = size
            System.arraycopy(source.array(), source.arrayOffset() + start, array, index, length)
            size += length

            return index
        } else {
            TODO()
        }
    }

    fun append(source: ByteBuffer, length: Int = source.remaining()): Int {
        require(length >= 0)
        ensureCapacity(length)

        val index = size
        source.get(array, index, length)
        size += length

        return index
    }

    private fun ensureCapacity(extra: Int) {
        val requiredCapacity = size + extra
        if (requiredCapacity > array.size) {
            var newArraySize = array.size
            do {
                newArraySize += newArraySize / 3
            } while (newArraySize < requiredCapacity)

            array = array.copyOf(newArraySize)
        }
    }
}
