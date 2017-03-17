package kotlinx.sockets

import java.util.concurrent.atomic.*

internal fun <T : Any> AtomicReference<T?>.setHandler(name: String, handler: T) {
    if (!compareAndSet(null, handler)) {
        throw IllegalStateException("$name is already pending")
    }
}

internal fun <T : Any> AtomicReference<T?>.take(): T = getAndSet(null) ?: throw IllegalStateException("No handler")
