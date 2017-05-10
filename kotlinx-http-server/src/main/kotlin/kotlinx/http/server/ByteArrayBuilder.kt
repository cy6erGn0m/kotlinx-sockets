package kotlinx.http.server

import java.nio.*

internal class ByteArrayBuilder(var array: ByteArray) {
    private var size = 0

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
