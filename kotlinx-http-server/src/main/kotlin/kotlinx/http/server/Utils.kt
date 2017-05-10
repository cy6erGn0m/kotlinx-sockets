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

        if (v in 0x41..0x5a) // A..Z
            v = 'a'.toInt() + (v - 'A'.toInt())

        hashCode = 31 * hashCode + v
    }

    return hashCode
}

internal fun ByteBuffer.hashCodeOfLowerCase(start: Int, length: Int): Int {
    var hashCode = 0
    for (pos in start..start + length - 1) {
        var v = get(pos).toInt()

        if (v in 0x41..0x5a) // A..Z
            v = 'a'.toInt() + (v - 'A'.toInt())

        hashCode = 31 * hashCode + v
    }

    return hashCode
}

internal fun equalsIgnoreCase(bb: ByteBuffer, start: Int, length: Int, s: String): Boolean {
    if (length != s.length) return false

    for (pos in start..start + length - 1) {
        var v = bb.get(pos).toInt()
        var v2 = s[pos - start].toInt()

        if (v == v2) continue

        if (v in 0x41..0x5a) // A..Z
            v = 'a'.toInt() + (v - 'A'.toInt())

        if (v2 in 0x41..0x5a) // A..Z
            v2 = 'a'.toInt() + (v2 - 'A'.toInt())

        if (v != v2) return false
    }

    return true
}

internal fun equalsIgnoreCase(bb: ByteArray, start: Int, length: Int, s: String): Boolean {
    if (length != s.length) return false

    for (pos in start..start + length - 1) {
        var v = bb[pos].toInt()
        var v2 = s[pos - start].toInt()

        if (v == v2) continue

        if (v in 0x41..0x5a) // A..Z
            v = 'a'.toInt() + (v - 'A'.toInt())

        if (v2 in 0x41..0x5a) // A..Z
            v2 = 'a'.toInt() + (v2 - 'A'.toInt())

        if (v != v2) return false
    }

    return true
}
