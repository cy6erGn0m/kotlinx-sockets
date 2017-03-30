package kotlinx.sockets.selector

import kotlinx.coroutines.experimental.*
import java.util.concurrent.atomic.*

class InterestSuspensionsMap {
    @Volatile
    @Suppress("unused")
    private var readHandlerReference: CancellableContinuation<Unit>? = null

    @Volatile
    @Suppress("unused")
    private var writeHandlerReference: CancellableContinuation<Unit>? = null

    @Volatile
    @Suppress("unused")
    private var connectHandlerReference: CancellableContinuation<Unit>? = null

    @Volatile
    @Suppress("unused")
    private var acceptHandlerReference: CancellableContinuation<Unit>? = null

    fun addSuspension(interest: SelectInterest, continuation: CancellableContinuation<Unit>) {
        val updater = updater(interest)

        val cancellation = if (continuation is CancellableContinuation<*>) {
            continuation.invokeOnCompletion { if (continuation.isCancelled) dropHandler(interest, continuation) }
        } else null

        if (!updater.compareAndSet(this, null, continuation)) {
            cancellation?.dispose()
            throw IllegalStateException("Handler for ${interest.name} is already registered")
        }
    }

    inline fun invokeIfPresent(interest: SelectInterest, block: CancellableContinuation<Unit>.() -> Unit): Boolean {
        return removeSuspension(interest)?.run { block(); true } ?: false
    }

    inline fun invokeForEachPresent(block: CancellableContinuation<Unit>.(SelectInterest) -> Unit) {
        for (interest in SelectInterest.values()) {
            removeSuspension(interest)?.run { block(interest) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun removeSuspension(interest: SelectInterest): CancellableContinuation<Unit>? = updater(interest).getAndSet(this, null) as CancellableContinuation<Unit>?

    private fun dropHandler(interest: SelectInterest, continuation: CancellableContinuation<Unit>) {
        updater(interest).compareAndSet(this, continuation, null)
    }

    companion object {
        private val updaters = SelectInterest.values().associateBy({ it }, { interest ->
            AtomicReferenceFieldUpdater.newUpdater(InterestSuspensionsMap::class.java, CancellableContinuation::class.java, "${interest.name.toLowerCase()}HandlerReference")
        })

        private fun updater(interest: SelectInterest) = updaters[interest] ?: throw IllegalArgumentException("interest $interest is not supported")
    }
}