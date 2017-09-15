package kotlinx.http.tls

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import javax.net.ssl.*
import kotlin.coroutines.experimental.*

suspend fun ReadWriteSocket.tls(trustManager: X509TrustManager? = null,
                        serverName: String? = null,
                        coroutineContext: CoroutineContext = CommonPool): ReadWriteSocket {
    val session = TLSClientSession(openReadChannel(), openWriteChannel(), trustManager, serverName, coroutineContext)
    val socket = ReadWriteImpl(session, this)

    try {
        session.negotiate()
    } catch (t: Throwable) {
        socket.close()
        session.output.close(t)
        throw t
    }

    return socket
}

suspend fun Socket.tls(trustManager: X509TrustManager? = null,
                   serverName: String? = null,
                   coroutineContext: CoroutineContext = CommonPool): Socket {
    val session = TLSClientSession(openReadChannel(), openWriteChannel(), trustManager, serverName, coroutineContext)
    val socket = TLSSocketImpl(session, this)

    try {
        session.negotiate()
    } catch (t: Throwable) {
        socket.close()
        session.output.close(t)
        throw t
    }

    return socket
}

private class TLSSocketImpl(val session: TLSClientSession, val delegate: Socket) : Socket by delegate {
    override fun attachForReading(channel: ByteChannel) = session.attachForReading(channel)
    override fun attachForWriting(channel: ByteChannel) = session.attachForWriting(channel)
}

private class ReadWriteImpl(val session: TLSClientSession, val delegate: ReadWriteSocket) : ReadWriteSocket by delegate {
    override fun attachForReading(channel: ByteChannel) = session.attachForReading(channel)
    override fun attachForWriting(channel: ByteChannel) = session.attachForWriting(channel)
}
