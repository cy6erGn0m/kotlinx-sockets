package kotlinx.sockets.examples.crawler

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.sockets.adapters.*
import java.nio.*
import java.nio.channels.*
import java.nio.file.*

class Receiver(val connections: ReceiveChannel<Connection>, val parse: SendChannel<Connection>, val pool: Channel<ByteBuffer>) {

    sealed class Event(val connection: Connection) {
        class End(connection: Connection) : Event(connection)
        class Failure(connection: Connection, val t: Throwable) : Event(connection)
        class Data(connection: Connection, val data: ByteBuffer) : Event(connection)
    }

    private val incoming: Channel<Event> = ArrayChannel(10000)

    suspend fun receiveStartLoop() {
        try {
            while (true) {
                val c = connections.receiveOrNull() ?: break
                if (c.depth > 2) continue

                launch(CommonPool) {
                    c.socket.receiveTo(incoming, pool) { buffer -> Event.Data(c, buffer) }.apply {
                        invokeOnCompletion { t ->
                            val message = when {
                                t != null -> Event.Failure(c, t)
                                else -> Event.End(c)
                            }

                            if (!incoming.offer(message)) {
                                launch(CommonPool) {
                                    incoming.send(message)
                                }
                            }
                        }
                        start()
                    }
                }
            }

            incoming.close()
        } catch (t: Throwable) {
            incoming.close(t)
        }
    }

    suspend fun receiveLoop() {
        try {
            while (true) {
                val e = incoming.receiveOrNull() ?: break

                when (e) {
                    is Event.Failure -> e.connection.close()
                    is Event.End -> {
                        e.connection.close()
                        parse.send(e.connection)
                    }
                    is Event.Data -> {
                        handleReceive(e.connection, e.data)
                    }
                }
            }

            parse.close()
        } catch (t: Throwable) {
            parse.close(t)
        }
    }

    internal fun handleReceive(c: Connection, m: ByteBuffer) {
        val fc = c.attachment as? FileChannel ?: run { c.openOutputChannel().also { c.attachment = it } }

        while (m.hasRemaining()) {
            fc.write(m)
        }
    }

    private fun Connection.openOutputChannel(): FileChannel {
        val f = outputFile()
        f.parentFile.mkdirs()
        return FileChannel.open(f.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    }
}