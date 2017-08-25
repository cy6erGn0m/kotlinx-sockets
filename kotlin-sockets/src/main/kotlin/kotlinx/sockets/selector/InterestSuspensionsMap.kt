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

        if (!updater.compareAndSet(this, null, continuation)) {
            throw IllegalStateException("Handler for ${interest.name} is already registered")
        }

        continuation.invokeOnCompletion { if (continuation.isCancelled) dropHandler(interest, continuation) }
    }

    inline fun invokeIfPresent(interest: SelectInterest, block: CancellableContinuation<Unit>.() -> Unit): Boolean {
        return removeSuspension(interest)?.run { block(); true } ?: false
    }

    inline fun invokeForEachPresent(block: CancellableContinuation<Unit>.(SelectInterest) -> Unit) {
        for (interest in SelectInterest.values()) {
            removeSuspension(interest)?.run { block(interest) }
        }
    }

    fun removeSuspension(interest: SelectInterest): CancellableContinuation<Unit>? = updater(interest).getAndSet(this, null)

    override fun toString(): String {
        return "R $readHandlerReference W $writeHandlerReference C $connectHandlerReference A $acceptHandlerReference"
    }

    private fun dropHandler(interest: SelectInterest, continuation: CancellableContinuation<Unit>) {
        updater(interest).compareAndSet(this, continuation, null)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val updaters = SelectInterest.values().map { interest ->
            AtomicReferenceFieldUpdater.newUpdater(InterestSuspensionsMap::class.java, CancellableContinuation::class.java, "${interest.name.toLowerCase()}HandlerReference") as AtomicReferenceFieldUpdater<InterestSuspensionsMap, CancellableContinuation<Unit>?>
        }.toTypedArray()

        private fun updater(interest: SelectInterest): AtomicReferenceFieldUpdater<InterestSuspensionsMap, CancellableContinuation<Unit>?> = updaters[interest.ordinal]
    }
}