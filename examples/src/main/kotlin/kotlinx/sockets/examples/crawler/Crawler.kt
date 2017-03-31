package kotlinx.sockets.examples.crawler

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.selector.*
import java.io.*
import java.net.*
import java.util.concurrent.*

fun main(args: Array<String>) {
    ExplicitSelectorManager().use { selector ->
        runBlocking(CommonPool) {
            Crawler().run(selector)
        }
    }
}

class Crawler {
    val visited = ConcurrentHashMap<String, Boolean>(100000)

    val urls = ArrayChannel<ConnectionRequest>(1024)
    private val pool = runDefaultByteBufferPool(10000)
    private val connections = ArrayChannel<Connection>(1000)
    private val parse = ArrayChannel<Connection>(1000)

    private val parser = URLParser(parse, urls, visited)
    private val requester = RequestSender(urls, connections)
    private val receiver = Receiver(connections, parse, pool)

    suspend fun run(selector: SelectorManager) {
        val initialUrls = listOf("http://kotlinlang.org/")
        initialUrls.forEach { urls.send(ConnectionRequest(it, 0)) }

        launch(CommonPool) {
            parser.parseLoop()
        }

        launch(CommonPool) {
            requester.connectAndRequestLoop(selector)
        }

        launch(CommonPool) {
            receiver.receiveStartLoop()
        }

        launch(CommonPool) {
            receiver.receiveLoop()
        }.join()

//        selectorLoop()
    }

//    sealed class SelectorResult {
//        object StopAdd : SelectorResult()
//        class Add(val connection: Connection) : SelectorResult()
//
//        class Receive(val connection: Connection, val message: ByteBuffer) : SelectorResult()
//        class Close(val connection: Connection) : SelectorResult()
//    }

    /*private suspend fun selectorLoop0() {
        val all = ArrayList<Connection>()
        var add = true

        while (all.isNotEmpty() || !connections.isClosedForReceive) {
            val s = selectUnbiased<SelectorResult> {
                if (add) {
                    connections.onReceiveOrNull { if (it == null) SelectorResult.StopAdd else SelectorResult.Add(it) }
                }

                for (c in all) {
                    c.input.onReceiveOrNull { if (it == null) SelectorResult.Close(c) else SelectorResult.Receive(c, it) }
                }
            }

            when (s) {
                SelectorResult.StopAdd -> add = false
                is SelectorResult.Add -> {
                    all.add(s.connection)
                    while (true) {
                        all.add(connections.poll() ?: break)
                    }
                }
                is SelectorResult.Close -> {
                    receiver.handleClose(s.connection, all)
                }
                is SelectorResult.Receive -> {
                    receiver.handleReceive(s, all)
                }
            }
        }

        parse.close()
    }*/

}

fun Connection.outputFile() = File("target/dl/", url.path + (if (url.path.endsWith("/") || url.path.isEmpty()) "index" else "") + ".html")

class ConnectionRequest(val url: String, val depth: Int)
class Connection(val url: URL, val socket: Socket, val depth: Int) {
    var attachment: Any? = null

    fun close() {
        socket.close()
        (attachment as? Closeable)?.close()
        attachment = null
    }
}

