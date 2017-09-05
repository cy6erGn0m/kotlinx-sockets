package kotlinx.http.impl

internal class MutableRange(var start: Int, var end: Int) {
    override fun toString(): String {
        return "MutableRange(start=$start, end=$end)"
    }
}