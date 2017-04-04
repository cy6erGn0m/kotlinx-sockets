package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import java.util.concurrent.*

fun main(args: Array<String>) {
    val count = 10000

    val pool = Executors.newFixedThreadPool(16)
    val dispatcher = pool.asCoroutineDispatcher()
    val r = ArrayBlockingQueue<Long>(count)
    val l = CountDownLatch(count)

    for (it in 1..count) {
        launch(dispatcher) {
            val time = numbersClientImpl(9096, false)
            println("$time ms")
            r.put(time)
        }.invokeOnCompletion { l.countDown() }
    }

    l.await()

    pool.shutdown()
    println("done")

    val all = r.toList()
    println("min ${all.min()} ms, max ${all.max()}, avg ${all.average()}")
}
