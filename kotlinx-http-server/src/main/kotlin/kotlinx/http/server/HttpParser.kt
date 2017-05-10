package kotlinx.http.server

import kotlinx.sockets.channels.*
import java.nio.*

internal class HttpParser(val parseBuffer: ByteBuffer, val headersBuffer: ByteBuffer) {
    private var state = State.Method
    private val headersBody: ByteArrayBuilder = ByteArrayBuilder(headersBuffer.array())
    private val headers = ArrayList<HeaderEntry>(16)

    private var expectedBody = true
    private var method: HttpMethod? = null
    private var methodHash: Int = 0
    private var uri: String? = null
    private var version: HttpVersion? = null

    suspend fun parse(ch: ReadChannel): HttpRequest? {
        readLoop@ while (true) {
            val rc = ch.read(parseBuffer)
            if (rc == -1) {
                if (state == State.Method) return null
                else throw ParserException("Unexpected EOF")
            }

            parseBuffer.flip()

            parseLoop@ while (true) {
                val result = when (state) {
                    State.Method -> parseMethod()
                    State.Uri -> parseUri()
                    State.Version -> parseVersion()
                    State.Headers -> parseHeader()
                    State.Body -> {
                        if (expectedBody) TODO()

                        parseBuffer.compact()
                        break@readLoop
                    }
                }

                if (!result) {
                    break@parseLoop
                }
            }

            parseBuffer.compact()
        }

        return HttpRequest(method!!, uri!!, version!!, headersBody.array, headers)
    }

    private fun parseMethod(): Boolean {
        if (selectUntilSpace(EOL.ShouldNotBe) { start, length ->
            val h = parseBuffer.hashCodeOfLowerCase(start, length)

            methodHash = h
            method = HttpMethod.lookup(h)
                    ?.takeIf { equalsIgnoreCase(parseBuffer, start, length, it.name) }
                    ?: HttpMethod(parseBuffer.stringOf(start, length), false)
        }) {
            expectedBody = method?.bodyExpected ?: false
            state = State.Uri
            return true
        }

        return false
    }

    private fun parseUri(): Boolean {
        if (selectUntilSpace(EOL.ShouldNotBe) { start, length ->
            uri = if (length <= 1) "/" else parseBuffer.stringOf(start, length)
        }) {
            state = State.Version
            return true
        }

        return false
    }

    private fun parseVersion(): Boolean {
        if (selectUntilSpace(EOL.ShouldBe) { start, length -> version = HttpVersion.byHash[parseBuffer.hashCodeOfLowerCase(start, length)] ?: throw ParserException("Unsupported HTTP version |${parseBuffer.stringOf(start, length)}|") }) {
            when (version!!) {
                HttpVersion.HTTP10 -> state = State.Body
                HttpVersion.HTTP11 -> state = State.Headers
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
            if (colon == -1) throw ParserException("Header should have colon, but got line |${parseBuffer.stringOf(colon, length)}|")

            nameLength = colon - start

            while (nameLength > 0 && parseBuffer.get(nameStart + nameLength - 1).isWhitespace()) {
                nameLength--
            }

            var valueStart = colon + 1
            var valueLength = length - (colon - start) - 1

            while (valueLength > 0 && parseBuffer.get(valueStart).isWhitespace()) {
                valueStart++
                valueLength--
            }

            val hash = parseBuffer.hashCodeOfLowerCase(nameStart, nameLength)
            nameStart = headersBody.append(parseBuffer, nameStart, nameLength)
            valueStart = headersBody.append(parseBuffer, valueStart, valueLength)

            headers.add(HeaderEntry(nameStart, nameLength, hash, valueStart, valueLength))
        }
    }

    private inline fun selectUntilSpace(eol: EOL, block: (Int, Int) -> Unit): Boolean {
        val end = selectCharacter(predicate = Char::isWhitespace)
        if (end == -1) return false
        val start = parseBuffer.position()

        when (eol) {
            EOL.ShouldBe -> if (!parseBuffer.get(end).isCrOrLf()) throw ParserException("Expected EOL but got extra characters in line |${parseBuffer.stringOf(parseBuffer.position(), end - start)}|")
            EOL.ShouldNotBe -> if (parseBuffer.get(end).isCrOrLf()) throw ParserException("EOL is unexpected")
            EOL.CouldBe -> Unit
        }

        block(start, end - start)

        parseBuffer.position(skipSpacesAndCrLf(end))
        return true
    }

    private inline fun selectUntilEol(block: (Int, Int) -> Unit): Boolean {
        val eol = select { it.isCrOrLf() }
        if (eol == -1) return false

        val start = parseBuffer.position()
        block(start, eol - start)

        parseBuffer.position(skipCrLf(eol))
        return true
    }

    private inline fun select(start: Int = parseBuffer.position(), predicate: (Byte) -> Boolean): Int {
        for (idx in start..parseBuffer.limit() - 1) {
            if (predicate(parseBuffer.get(idx))) return idx
        }

        return -1
    }

    private inline fun selectCharacter(start: Int = parseBuffer.position(), predicate: (Char) -> Boolean): Int = select(start) { predicate(it.toChar()) }

    private fun skipSpacesAndCrLf(pos: Int): Int {
        var newPosition = pos

        while (newPosition < parseBuffer.limit() && parseBuffer.get(newPosition).let { it.isWhitespace() && !it.isCrOrLf() })
            newPosition++

        return skipCrLf(newPosition)
    }

    private fun skipCrLf(pos: Int): Int {
        if (pos >= parseBuffer.limit()) return pos

        var newPosition = pos

        val first = parseBuffer.get(newPosition)
        if (first == CR) {
            newPosition++
            if (newPosition < parseBuffer.limit() && parseBuffer.get(newPosition) == LF) {
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
