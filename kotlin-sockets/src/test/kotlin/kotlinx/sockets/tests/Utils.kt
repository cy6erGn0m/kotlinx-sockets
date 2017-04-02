package kotlinx.sockets.tests

import kotlinx.coroutines.experimental.*

internal suspend fun Job.joinOrFail() {
    join()
    invokeOnCompletion { t -> if (t != null) throw t }
}
