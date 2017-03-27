package kotlinx.sockets.examples.dns

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
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
            val results = selector.resolve(pool, dnsServer, "kotlinlang.org", Type.A)

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
        is Resource.A -> println("A ${name.joinToString(".")} $address (ttl $ttl sec)")
        is Resource.AAAA -> println("A ${name.joinToString(".")} $address (ttl $ttl sec)")
        is Resource.Ns -> println("NS ${name.joinToString(".")} ${nameServer.joinToString(".")} (ttl $ttl sec)")
        is Resource.SOA -> println("SOA ${name.joinToString(".")} MNAME ${mname.joinToString(".")}, RNAME ${rname.joinToString(".")}, serial $serial, refresh $refresh sec, retry $retry sec, expire $expire sec, minimum $minimum sec")
        is Resource.Opt -> {}
        else -> println("$type ${name.joinToString(".")} ???")
    }
}

private fun inet4address(a: Int, b: Int, c: Int, d: Int): Inet4Address {
    return InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())) as Inet4Address
}

private suspend fun SelectorManager.resolve(pool: Channel<ByteBuffer>, server: Inet4Address, host: String, type: Type): Message {
    val client = socket()
    client.setOption(StandardSocketOptions.TCP_NODELAY, true)
    client.connect(InetSocketAddress(server, 53))

    return client.use {
        val output = client.openSendChannel(pool).binary(pool, ByteOrder.BIG_ENDIAN)
        val input = client.openReceiveChannel(pool).binary(pool, ByteOrder.BIG_ENDIAN)

        doResolve(output, input, host, type)
    }
}

private suspend fun doResolve(out: BinaryWriteChannel, input: BinaryReadChannel, host: String, type: Type): Message {
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

    out.write(message, encoder, true)
    out.flush()

    val result = input.readMessage(true)

    if (result.header.id != id) {
        System.err.println("Bad response id")
    }
    return result
}

private fun SendChannel<ByteBuffer>.binary(pool: Channel<ByteBuffer>, order: ByteOrder = ByteOrder.BIG_ENDIAN) = BinaryWriteChannel(this, pool, order)
private fun ReceiveChannel<ByteBuffer>.binary(pool: Channel<ByteBuffer>, order: ByteOrder = ByteOrder.BIG_ENDIAN) = BinaryReadChannel(this, pool, order)
