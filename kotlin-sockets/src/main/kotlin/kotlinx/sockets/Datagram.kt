package kotlinx.sockets

import kotlinx.coroutines.experimental.io.packet.*
import java.net.*

class Datagram(val packet: ByteReadPacket, val address: SocketAddress)