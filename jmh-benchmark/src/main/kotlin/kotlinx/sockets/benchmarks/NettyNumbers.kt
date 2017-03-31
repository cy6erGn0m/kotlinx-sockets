package kotlinx.sockets.benchmarks

import io.netty.bootstrap.*
import io.netty.channel.*
import io.netty.channel.nio.*
import io.netty.channel.socket.*
import io.netty.channel.socket.nio.*
import io.netty.handler.codec.*
import io.netty.handler.codec.string.*
import io.netty.handler.logging.*
import java.util.*
import java.util.logging.*
import kotlin.concurrent.*

enum class Command {
    SUM,
    AVG
}

class NettyNumbersServer {
    enum class ServerSessionState {
        HELLO,
        COMMAND,
        NUMBERS,
        END

    }

    class NumbersServerHandler : SimpleChannelInboundHandler<String>() {
        private val logger = Logger.getLogger("server")
        private var state = ServerSessionState.HELLO
        private var command: Command? = null

        public override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
            when (state) {
                ServerSessionState.HELLO -> handleHello(ctx, msg)
                ServerSessionState.COMMAND -> handleCommand(ctx, msg)
                ServerSessionState.NUMBERS -> handleNumbersAndProcessCommand(ctx, msg)
                ServerSessionState.END -> {
                }
            }
        }

        private fun handleHello(ctx: ChannelHandlerContext, msg: String) {
            when (msg) {
                "" -> {
                }
                "HELLO" -> {
                    ctx.writeAndFlush("EHLLO")
                    state = ServerSessionState.COMMAND
                }
                else -> {
                    logger.warning("Invalid HELLO $msg from client ${ctx.channel().remoteAddress()}")
                    ctx.writeAndFlush("ERROR Wrong hello").addListener { f ->
                        f.get()
                        ctx.close()
                    }
                }
            }
        }

        private fun handleCommand(ctx: ChannelHandlerContext, msg: String) {
            when (msg) {
                "" -> {
                }
                "SUM" -> {
                    command = Command.SUM
                    state = ServerSessionState.NUMBERS
                }
                "AVG" -> {
                    command = Command.AVG
                    state = ServerSessionState.NUMBERS
                }
                "BYE" -> {
                    state = ServerSessionState.END
                    ctx.writeAndFlush("BYE").addListener { f ->
                        f.get()
                        ctx.close()
                    }
                }
                else -> {
                    logger.warning("Invalid command $msg from client ${ctx.channel().remoteAddress()}")
                    ctx.writeAndFlush("ERROR Wrong command").addListener { f ->
                        f.get()
                        ctx.close()
                    }
                }
            }
        }

        private fun handleNumbersAndProcessCommand(ctx: ChannelHandlerContext, msg: String) {
            requireNotNull(command)

            if (msg.isNotBlank()) {
                val numbers = msg.split(",").filter(String::isNotBlank).map(String::toInt)

                when (command) {
                    null -> throw IllegalStateException("command is not assigned")
                    Command.SUM -> handleSum(ctx, numbers)
                    Command.AVG -> handleAvg(ctx, numbers)
                }

                state = ServerSessionState.COMMAND
            }
        }

        private fun handleSum(ctx: ChannelHandlerContext, numbers: List<Int>) {
            ctx.writeAndFlush(numbers.sum().toString())
        }

        private fun handleAvg(ctx: ChannelHandlerContext, numbers: List<Int>) {
            ctx.writeAndFlush(numbers.average().toString())
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
        }

        @Suppress("OverridingDeprecatedMember")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }

    class NumbersServerInitializer : ChannelInitializer<SocketChannel>() {

        public override fun initChannel(ch: SocketChannel) {
            val pipeline = ch.pipeline()
            ch.config().soLinger = 0

            pipeline.addLast(LineBasedFrameDecoder(65536))
            pipeline.addLast(StringDecoder(Charsets.UTF_8))
            pipeline.addLast(LineEncoder(LineSeparator.UNIX, Charsets.UTF_8))

            pipeline.addLast(NumbersServerHandler())
        }
    }

    companion object {
        fun start(port: Int?, log: Boolean): Channel {
            val connectorGroup = NioEventLoopGroup(1)
            val workerGroup = NioEventLoopGroup()

            val b = ServerBootstrap()
            b.group(connectorGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .handler(LoggingHandler(if (log) LogLevel.INFO else LogLevel.TRACE))
                    .childHandler(NumbersServerInitializer())

            val ch = b.bind(port ?: 51586).sync().channel()
            ch.closeFuture().addListener {
                connectorGroup.shutdownGracefully()
                workerGroup.shutdownGracefully()
            }

            return ch
        }

        @JvmStatic
        fun main(args: Array<String>) {
            start(9096, true)
        }
    }
}


class NettyNumbersClient {
    enum class ClientSessionState {
        HELLO,
        RESPONSE,
        BYE,
        END
    }

    class NumbersClientHandler(val log: Boolean) : SimpleChannelInboundHandler<String>() {
        private var state = ClientSessionState.HELLO
        private var command: Command = Command.SUM
        private var numbers: List<Int>? = null
        private var commandCount = 0
        private var random = Random()
        private val start = System.currentTimeMillis()

        override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
            when (state) {
                ClientSessionState.HELLO -> handleHello(ctx, msg)
                ClientSessionState.RESPONSE -> handleResponse(ctx, msg)
                ClientSessionState.BYE -> handleBye(ctx, msg)
                ClientSessionState.END -> {
                }
            }
        }

        private fun handleHello(ctx: ChannelHandlerContext, msg: String) {
            when (msg) {
                "EHLLO" -> {
                    state = ClientSessionState.RESPONSE
                    sendRequest(ctx)
                }
                else -> {
                    ctx.close()
                    ctx.fireExceptionCaught(IllegalStateException("Wrong hello response from server"))
                }
            }
        }

        private fun handleResponse(ctx: ChannelHandlerContext, msg: String) {
            when (command) {
                Command.AVG -> {
                    val r = msg.toDouble()
                    if (numbers!!.average() != r) {
                        System.err.println("Invalid response from server: $msg for request $numbers")
                        ctx.close()
                    } else {
                        if (log) {
                            println("AVG($numbers) = $r")
                        }
                        sendRequest(ctx)
                    }
                }
                Command.SUM -> {
                    val r = msg.toInt()
                    if (numbers!!.sum() != r) {
                        System.err.println("Invalid response from server: $msg for request $numbers")
                        ctx.close()
                    } else {
                        if (log) {
                            println("SUM($numbers) = $r")
                        }
                        sendRequest(ctx)
                    }
                }
            }
        }

        private fun handleBye(ctx: ChannelHandlerContext, msg: String) {
            when (msg) {
                "BYE" -> {
                    state = ClientSessionState.END
                    ctx.close()
                    if (log) {
                        println("time: ${System.currentTimeMillis() - start} ms")
                    }
                }
                else -> {
                    System.err.println("Failed to complete bye")
                    ctx.close()
                }
            }
        }

        private fun sendRequest(ctx: ChannelHandlerContext) {
            if (commandCount > 200) {
                when (command) {
                    Command.SUM -> command = Command.AVG
                    Command.AVG -> {
                        state = ClientSessionState.BYE
                        ctx.writeAndFlush("BYE")
                        return
                    }
                    else -> {
                        ctx.close()
                        return
                    }
                }
                commandCount = 0
            }

            commandCount++

            when (command) {
                Command.SUM -> ctx.write("SUM")
                Command.AVG -> ctx.write("AVG")
            }

            numbers = random.randomNumbers()
            ctx.writeAndFlush(numbers!!.joinToString(","))
        }

        private fun Random.randomNumbers() = (1..1 + nextInt(10)).map { nextInt(50) }
    }

    class NumbersClientInitializer(val log: Boolean) : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.config().soLinger = 1
            ch.pipeline().apply {
                addLast(LineBasedFrameDecoder(65536))
                addLast(StringDecoder(Charsets.UTF_8))
                addLast(LineEncoder(LineSeparator.UNIX, Charsets.UTF_8))

                addLast(NumbersClientHandler(log))
            }
        }
    }

    companion object {
        fun start(port: Int, log: Boolean): Channel {
            val group = NioEventLoopGroup()
            val ch = start(group, port, log)
            ch.closeFuture().addListener {
                group.shutdownGracefully()
            }

            return ch
        }

        fun start(group: EventLoopGroup, port: Int, log: Boolean): Channel {
            val b = Bootstrap()
            b.group(group)
                    .channel(NioSocketChannel::class.java)
                    .handler(NumbersClientInitializer(log))

            val f = b.connect("localhost", port).sync()
            f.channel().writeAndFlush("HELLO")
            return f.channel()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val ch = start(9096, true)
            ch.closeFuture().sync()
            ch.close()
        }
    }
}