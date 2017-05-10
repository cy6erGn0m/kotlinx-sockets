package kotlinx.http.server.ktor

import kotlinx.http.server.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.util.*

internal class LazyValuesMap(val request: HttpRequest) : ValuesMap {
    private val content = CaseInsensitiveMap<Any>()

    private val converted by lazy {
        content.mapValues { (_, u) ->
            @Suppress("UNCHECKED_CAST")
            if (u is HeaderEntry) listOf(u.value(request.headersBody))
            else (u as List<HeaderEntry>).map { it.value(request.headersBody) }
        }
    }

    override val caseInsensitiveKey: Boolean
        get() = true

    init {
        for (h in request.headers) {
            val name = h.name(request.headersBody)
            val existing = content[name]

            @Suppress("UNCHECKED_CAST")
            when (existing) {
                null -> content[name] = h
                is HeaderValue -> content[name] = mutableListOf(existing, h)
                else -> (existing as MutableList<HeaderEntry>).add(h)
            }
        }
    }

    override fun entries(): Set<Map.Entry<String, List<String>>> {
        return converted.entries
    }

    override fun getAll(name: String): List<String>? {
        val u = content[name]

        @Suppress("UNCHECKED_CAST")
        return when (u) {
            null -> null
            is HeaderEntry -> listOf(u.value(request.headersBody))
            else -> (u as List<HeaderEntry>).map { it.value(request.headersBody) }
        }
    }

    override fun isEmpty(): Boolean {
        return request.headers.isEmpty()
    }

    override fun names(): Set<String> {
        return content.keys
    }
}
