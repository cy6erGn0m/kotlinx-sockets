package kotlinx.sockets.examples.networkers

import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import java.nio.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

abstract class NetWorker(val interestFlag: Int) {
    class Connection(val socketChannel: SocketChannel, val out: ByteBuffer, val input: ByteBuffer) {
        var closed = false
        var availableForRead = 0
        val requestRead = AtomicBoolean()
        val requestWrite = AtomicBoolean()
    }

    private val initialCapacity = 1000
    private val newcomers = ArrayBlockingQueue<Connection>(1000)

    private val idle = ArrayList<Connection>()
    private val ready = ArrayList<Connection>(initialCapacity)
    private val pending = ArrayList<Connection>(initialCapacity)
    private val changeInterest = ArrayList<Connection>(initialCapacity)

    private val buffer = ByteBuffer.allocateDirect(8192)
    private val tmp = ArrayList<Connection>(initialCapacity)

    fun run() {
        val selector: Selector = Selector.open()

        while (true) {
            processNewcomers(selector)
            processReady()
            processIdle()
            applyInterests(selector)


        }
    }

    protected abstract fun jobRequested(c: Connection): Boolean
    protected abstract fun processJob(c: Connection): Boolean

    private fun processIdle() {
        for (i in 0 until idle.size) {
            val c = idle[i]

            if (jobRequested(c)) {
                ready.add(c)
            } else if (c.closed) {
            } else {
                tmp.add(c)
            }
        }

        idle.clear()
        idle.addAll(tmp)
        tmp.clear()
    }

    private fun processReady() {
        for (i in 0 until ready.size) {
            val c = ready[i]

            if (!jobRequested(c)) {
                idle.add(c)
            } else if (processJob(c)) {
                tmp.add(c)
            } else if (!c.closed) {
                changeInterest.add(c)
            }
        }

        ready.clear()
        ready.addAll(tmp)
        tmp.clear()
    }

    private fun applyInterests(selector: Selector) {
        for (i in 0 until changeInterest.size) {
            val c = changeInterest[i]
            if (applyInterest(c, selector)) {
                pending.add(c)
            }
        }
        changeInterest.clear()
    }

    private fun applyInterest(c: Connection, selector: Selector): Boolean {
        try {
            c.socketChannel.keyFor(selector).interestOps(interestFlag)
            return true
        } catch (t: Throwable) {
            c.socketChannel.close()
            return false
        }
    }

    private fun processNewcomers(selector: Selector) {
        do {
            val c = newcomers.poll() ?: break
            c.socketChannel.register(selector, 0)
            idle.add(c)
        } while (true)
    }
}