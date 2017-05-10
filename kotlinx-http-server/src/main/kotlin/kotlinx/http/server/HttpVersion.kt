package kotlinx.http.server

enum class HttpVersion(val text: String) {
    HTTP10("HTTP/1.0"),
    HTTP11("HTTP/1.1");

    companion object {
        val byHash = values().associateBy { it.text.hashCodeLowerCase() }
    }
}