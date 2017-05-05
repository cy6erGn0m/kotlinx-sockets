package kotlinx.http.server

import java.nio.*

internal fun ByteArray.stringOf(start: Int, length: Int): String {
    return String(this, start, length, Charsets.ISO_8859_1)
}

internal fun ByteBuffer.stringOf(start: Int, length: Int): String {
    require(hasArray())

    return String(array(), arrayOffset() + start, length, Charsets.ISO_8859_1)
}

internal fun String.hashCodeLowerCase(): Int {
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

internal fun ByteBuffer.hashCodeOfLowerCase(start: Int, length: Int): Int {
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
