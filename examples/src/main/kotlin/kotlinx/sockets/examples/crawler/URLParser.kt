package kotlinx.sockets.examples.crawler

import kotlinx.coroutines.experimental.channels.*
import java.io.*

class URLParser(val parse: ReceiveChannel<Connection>, val urls: SendChannel<ConnectionRequest>, val visited: MutableMap<String, Boolean>) {
    private val httpUrlRegexp = """http://([\da-z.-]+)\.([a-z.]{2,6})([/\w .-]*)*/?""".toRegex()

    suspend fun parseLoop() {
        while (true) {
            parse(parse.receiveOrNull() ?: break)
        }

        urls.close()
    }

    private suspend fun parse(c: Connection) {
        c.close()
        val depth = c.depth + 1

        FileInputStream(c.outputFile()).reader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                httpUrlRegexp.findAll(line).forEach {
                    val url = it.value

                    if (visited.put(url, true) == null) {
                        urls.send(ConnectionRequest(url, depth))
                    }
                }
            }
        }
    }

}