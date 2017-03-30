package kotlinx.sockets.selector

import java.io.*
import java.nio.channels.spi.*

/**
 * Selector manager is a service that manages NIO selectors and selection threads
 */
interface SelectorManager {
    val provider: SelectorProvider

    /**
     * Notifies selector that interest ops has been changed
     */
    fun notifyInterest(s: AsyncSelectable)

    /**
     * Notifies selector that interest ops has been changed.
     * Should be only called from the selector loop
     */
    fun notifyInterestDirect(s: AsyncSelectable)

    /**
     * Notifies the selector that selectable has been closed.
     */
    fun notifyClosed(s: AsyncSelectable)

    companion object {
        val DefaultSelectorManager = OnDemandSelectorManager()
    }
}

/**
 * Creates a NIO entity via [create] and calls [setup] on it. If any exception happens then the entity will be closed
 * and an exception will be propagated.
 */
inline fun <C : Closeable, R> SelectorManager.buildOrClose(create: SelectorProvider.() -> C, setup: C.() -> R): R {
    while (true) {
        val result = create(provider)

        try {
            return setup(result)
        } catch (t: Throwable) {
            result.close()
            throw t
        }
    }
}