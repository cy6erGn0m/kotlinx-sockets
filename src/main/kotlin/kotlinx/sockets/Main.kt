package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.future.*
import java.net.*
import java.nio.*
import kotlin.system.*

fun main(args: Array<String>) {
    SelectorManager().use { manager ->
        manager.start()

        future(CommonPool) {
            manager.socket().use { socket ->
                socket.connect(InetSocketAddress(9098))
                println("Connected")

                val bb = ByteBuffer.allocate(8192)
                val cb = CharBuffer.allocate(8192)
                val decoder = Charsets.UTF_8.newDecoder()

                while (true) {
                    val rc = socket.read(bb)

                    bb.flip()
                    decoder.decode(bb, cb, rc == -1)
                    bb.compact()
                    cb.flip()

                    while (cb.hasRemaining()) {
                        val eolIndex = cb.indexOf('\n')
                        val lineChars = if (eolIndex != -1) (eolIndex + 1) else if (rc == -1) cb.remaining() else break

                        var endIndex = lineChars
                        while (endIndex > 0 && cb.get(endIndex - 1).isWhitespace()) {
                            endIndex--
                        }

                        if (endIndex > 0) {
                            processCommand(cb.subSequence(0, endIndex).toString(), socket)
                        }

                        cb.position(cb.position() + lineChars)
                    }

                    cb.compact()

                    if (rc == -1) break
                }
            }
        }.get()
    }
}

private suspend fun processCommand(line: String, socket: AsyncSocket<*>) {
    when {
        line.startsWith("exit") -> {
            println("Got exit")
            socket.respond("Bye.\n\n")
            socket.channel.close()
            exitProcess(0)
        }
        line.startsWith("id") -> {
            socket.respond("ID: ${socket.channel.remoteAddress}\n")
        }
        else -> {
            socket.respond("Unknown command: $line\n")
        }
    }
}

private suspend fun AsyncSocket<*>.respond(text: String) {
    write(ByteBuffer.wrap(text.toByteArray()))
}

private fun StringBuilder.clear() {
    delete(0, length)
}