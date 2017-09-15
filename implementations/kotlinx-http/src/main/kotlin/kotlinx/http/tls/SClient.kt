package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.selector.*
import java.net.*
import kotlin.system.*

fun main(args: Array<String>) {
    var host = "localhost"
    var port = 443

    val it = args.iterator()
    while (it.hasNext()) {
        val arg = it.next()

        if (arg.startsWith("-")) {
            when (arg) {
                "-h", "-?", "-help", "--help" -> printHelp()
                else -> {
                    System.err.println("Invalid option $arg")
                    printHelp()
                    exitProcess(1)
                }
            }
        } else {
            host = arg.substringBefore(":")
            port = arg.substringAfter(":").toInt()
            if (it.hasNext()) {
                System.err.println("Unexpected extra arguments: ${it.asSequence().joinToString(" ")}")
                printHelp()
                exitProcess(1)
            }
        }
    }

    val remoteAddress = InetSocketAddress(host, port)

    runBlocking {
        ActorSelectorManager().use { selector ->
            aSocket(selector).tcp().connect(remoteAddress).use { socket ->
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()

                val session = TLSClientSession(input, output)
                launch(CommonPool) {
                    session.run()
                }

                launch(CommonPool) {
                    try {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val rc = System.`in`.read(buffer)
                            if (rc == -1) break
                            session.appDataOutput.writeFully(buffer, 0, rc)
                            session.appDataOutput.flush()
                        }
                    } finally {
                        session.appDataOutput.close()
                    }
                }

                val bb = ByteBuffer.allocate(8192)
                while (true) {
                    val rc = session.appDataInput.readAvailable(bb)
                    if (rc == -1) break
                    bb.flip()
                    System.out.write(bb.array(), bb.arrayOffset() + bb.position(), rc)
                    System.out.flush()
                }
            }
        }
    }
}

private fun printHelp() {
    println("java ... SClientKt [-h|-?|-help|--help] host[:port]")
}