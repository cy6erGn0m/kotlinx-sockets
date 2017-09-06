package kotlinx.http

import kotlinx.http.internals.*

class HttpMethod(val name: CharSequence) {
    override fun toString() = name.toString()

    companion object {
        val GET = HttpMethod("GET")
        val POST = HttpMethod("POST")
        val PUT = HttpMethod("PUT")
        val DELETE = HttpMethod("DELETE")
        val HEAD = HttpMethod("HEAD")
        val OPTIONS = HttpMethod("OPTIONS")

        val allDefaults = listOf(GET, POST, PUT, DELETE, HEAD, OPTIONS)
        internal val defaults = run {
            AsciiCharTree.build(allDefaults, { it.name.length }, { m, idx -> m.name[idx] })
        }
    }
}