package kotlinx.http.server

class HeaderEntry(val nameStart: Int, val nameLength: Int, val nameHash: Int, val valueStart: Int, val valueLength: Int) {
    private var _name: String? = null
    private var _value: String? = null

    fun name(array: ByteArray): String = _name ?: array.stringOf(nameStart, nameLength).also { _name = it }

    fun value(array: ByteArray): String = _value ?: array.stringOf(valueStart, valueLength).also { _value = it }
}
