package kotlinx.http.server

import kotlinx.sockets.channels.*
import java.nio.*

internal class HttpParser(val bb: ByteBuffer) {
    private var state = State.Method
    private val headersBody: ByteArrayBuilder = ByteArrayBuilder()
    private val headers = ArrayList<HeaderEntry>()

    private var expectedBody = true
    private var method: HttpMethod? = null
    private var methodHash: Int = 0
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
        if (selectUntilSpace(EOL.ShouldNotBe) { start, length ->
            val h = bb.hashCodeOfLowerCase(start, length)
            val s = bb.stringOf(start, length)

            methodHash = h
            method = HttpMethod.lookup(h)?.takeIf { it.name.equals(s, ignoreCase = true) } ?: HttpMethod(s, false)
        }) {
            expectedBody = method?.bodyExpected ?: false
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

            val hash = bb.hashCodeOfLowerCase(nameStart, nameLength)
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
