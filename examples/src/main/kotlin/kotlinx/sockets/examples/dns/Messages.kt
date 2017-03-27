package kotlinx.sockets.examples.dns

import java.net.*


enum class Opcode(val value: Int) {
    Query(0),
    InverseQuery(1),
    ServerStatus(2);

    companion object {
        val byValue = values().associateBy { it.value }
    }
}

enum class ResponseCode(val value: Int) {
    OK(0),
    FormatError(1),
    ServerFailure(2),
    NameError(3),
    NotImplemented(4),
    Refused(5);

    companion object {
        val byValue = values().associateBy { it.value }
    }
}

enum class Type(val value: Int) {
    A(1),
    AAAA(28),
    NS(2),
    CNAME(5),
    SOA(6),
    PTR(12),
    MX(15),
    TXT(16),
    // obsolete and irrelevant values skipped here to simplify example
    ARequestForATransfer(252), // 252 A request for a transfer of an entire zone
    ARequestForMailbox(253),
    ALL(255),

    OPT(41);

    companion object {
        val byValue = values().associateBy(Type::value)
    }
}

enum class Class(val value: Int) {
    Internet(1),
    Chaos(3),
    Hesiod(4),
    Any(255);

    companion object {
        val byValue = values().associateBy(Class::value)
    }
}

data class Header(val id: Short,
                  val isQuery: Boolean,
                  val opcode: Opcode,
                  val authoritativeAnswer: Boolean,
                  val truncation: Boolean,
                  val recursionDesired: Boolean,
                  val recursionAvailable: Boolean,
                  val authenticData: Boolean,
                  val checkingDisabled: Boolean,
                  val responseCode: ResponseCode,
                  val questionsCount: Int,
                  val answersCount: Int,
                  val nameServersCount: Int,
                  val additionalResourcesCount: Int)

data class Question(val name: List<String>, val type: Type, val qclass: Class)
sealed class Resource<out D>(val name: List<String>, val type: Type, val length: Int, val data: D) {
    class Opt(name: List<String>, val udpPayloadSize: Int, val extendedRCode: Byte, val version: Byte) : Resource<Nothing?>(name, Type.OPT, 0, null)
    class CName(name: List<String>, val cname: List<String>, val ttl: Int) : Resource<List<String>>(name, Type.CNAME, cname.sumBy { it.length + 1 } + 1, cname)
    class A(name: List<String>, val qclass: Class, val address: Inet4Address, val ttl: Long) : Resource<Inet4Address>(name, Type.A, 4, address)
    class AAAA(name: List<String>, val qclass: Class, val address: Inet6Address, val ttl: Long) : Resource<Inet6Address>(name, Type.A, 4, address)
    class Ns(name: List<String>, val qclass: Class, val nameServer: List<String>, val ttl: Long) : Resource<List<String>>(name, Type.A, 4, nameServer)
    class SOA(name: List<String>, val mname: List<String>, val rname: List<String>, val serial: Long, val refresh: Long, val retry: Long, val expire: Long, val minimum: Long) : Resource<Nothing?>(name, Type.SOA, 4, null)
}

class Message(val header: Header, val questions: List<Question>, val answers: List<Resource<*>>, val nameServers: List<Resource<*>>, val additional: List<Resource<*>>)
