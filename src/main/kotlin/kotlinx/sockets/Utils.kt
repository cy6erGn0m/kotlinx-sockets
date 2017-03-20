package kotlinx.sockets

import java.nio.channels.*
import java.util.concurrent.atomic.*

/**
 * Attempts to set handler atomically or throws [IllegalStateException] if a handler has been already set
 */
internal fun <T : Any> AtomicReference<T?>.setHandler(name: String, handler: T) {
    if (!compareAndSet(null, handler)) {
        throw IllegalStateException("$name is already pending")
    }
}

/**
 * Swaps null and current object and return it if it was existed or throws [IllegalStateException]
 */
internal fun <T : Any> AtomicReference<T?>.take(): T = getAndSet(null) ?: throw IllegalStateException("No handler")

/**
 * Swaps null and current object and invokes [block] on then object if it was present
 * @return true if object was present and [block] was invoked, false otherwise
 */
internal fun <T : Any> AtomicReference<T?>.invokeIfPresent(block: T.() -> Unit): Boolean = getAndSet(null)?.let { block(it); true } ?: false

internal fun SelectionKey.readyOp(op: Int) = readyOps() and op != 0