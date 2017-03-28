package kotlinx.sockets.examples.dns

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import kotlinx.sockets.adapters.*
import kotlinx.sockets.channels.*
import kotlinx.sockets.selector.*
import java.net.*
import java.nio.*
import java.util.*

fun main(args: Array<String>) {
    SelectorManager().use { selector ->
        runBlocking {
            val pool = runDefaultByteBufferPool()

//            val dnsServer = inet4address(198, 41, 0, 4) // root DNS server
//            val dnsServer = inet4address(8, 8, 8, 8) // google DNS server
            val dnsServer = inet4address(77, 88, 8, 8) // yandex DNS server
            val results = selector.resolve(pool, dnsServer, "kotlinlang.org", Type.A, tcp = true)

            if (results.answers.isNotEmpty()) {
                println("Answers:")
                results.answers.forEach { it.printRecord() }
            }

            if (results.nameServers.isNotEmpty()) {
                println()
                println("Name servers:")
                results.nameServers.forEach { it.printRecord() }
            }

            if (results.additional.isNotEmpty()) {
                println()
                println("Additional:")
                results.additional.forEach { it.printRecord() }
            }
        }
    }
}

private fun Resource<*>.printRecord() {
    when (this) {
        is Resource.A -> println("${type.name} ${name.joinToString(".")} $address (ttl $ttl sec)")
        is Resource.Ns -> println("NS ${name.joinToString(".")} ${nameServer.joinToString(".")} (ttl $ttl sec)")
        is Resource.SOA -> println("SOA ${name.joinToString(".")} MNAME ${mname.joinToString(".")}, RNAME ${rname.joinToString(".")}, serial $serial, refresh $refresh sec, retry $retry sec, expire $expire sec, minimum $minimum sec")
        is Resource.CName -> println("CNAME ${name.joinToString(".")} ${cname.joinToString(".")} (ttl $ttl sec)")
        is Resource.Opt -> println("OPT ${name.takeIf { it.isNotEmpty() }?.joinToString(".") ?: "<root>"}")
        is Resource.Text -> println("TEXT ${name.joinToString(".")} $texts")
        is Resource.MX -> println("MX ${name.joinToString(".")}, preference $preference, exchange ${exchange.joinToString(".")}")
        else -> println("$type ${name.joinToString(".")} ???")
    }
}

private fun inet4address(a: Int, b: Int, c: Int, d: Int): InetAddress {
    return InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}

private suspend fun SelectorManager.resolve(pool: Channel<ByteBuffer>, server: InetAddress, host: String, type: Type, tcp: Boolean): Message {
    val client: AReadWriteSocket = if (tcp) {
        socket().run {
            setOption(StandardSocketOptions.TCP_NODELAY, true)
            connect(InetSocketAddress(server, 53))
        }
    } else {
        datagramSocket().connect(InetSocketAddress(server, 53))
    }

    return client.use {
        val output = client.openSendChannel(pool).bufferedWrite(pool, ByteOrder.BIG_ENDIAN)
        val input = client.openReceiveChannel(pool).bufferedRead(pool, ByteOrder.BIG_ENDIAN)

        doResolve(output, input, host, type, tcp)
    }
}

private suspend fun doResolve(out: BufferedWriteChannel, input: BufferedReadChannel, host: String, type: Type, tcp: Boolean): Message {
    val rnd = Random()
    val id = (rnd.nextInt() and 0xffff).toShort()

    val message = Message(
            Header(id, true, Opcode.Query, false, false, true, false, true, false, ResponseCode.OK, 1, 0, 0, 1),
            listOf(
                    Question(host.split('.'), type, Class.Internet)
            ),
            emptyList(), emptyList(),
            additional = listOf(
                    Resource.Opt(emptyList(), 4096, 0, 0)
            )
    )

    val encoder = Charsets.ISO_8859_1.newEncoder()

    out.write(message, encoder, tcp)
    out.flush()

    val result = input.readMessage(tcp)

    if (result.header.id != id) {
        System.err.println("Bad response id")
    }
    return result
}
