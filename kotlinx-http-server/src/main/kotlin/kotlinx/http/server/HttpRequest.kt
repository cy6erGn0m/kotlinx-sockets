package kotlinx.http.server

class HttpRequest(val method: HttpMethod, val uri: String, val version: String, val headersBody: ByteArray, val headers: ArrayList<HeaderEntry>) {

    fun header(name: String): List<HeaderEntry> {
        val h = name.hashCodeLowerCase()

        return headers.filter {
            it.nameHash == h &&
                    it.name(headersBody).equals(name, ignoreCase = true)
        }
    }

    fun headerFirst(name: String): HeaderEntry? {
        val h = name.hashCodeLowerCase()

        return headers.firstOrNull {
            it.nameHash == h &&
                    it.name(headersBody).equals(name, ignoreCase = true)
        }
    }
}
