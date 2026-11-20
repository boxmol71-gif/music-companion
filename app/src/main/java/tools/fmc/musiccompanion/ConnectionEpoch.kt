package tools.fmc.musiccompanion

/**
 * Identifies the one live GATT connection.
 *
 * Android may deliver a callback after a request timed out. A callback from the old connection must not
 * complete work belonging to a replacement connection, so every replacement gets a new identity and stale
 * identities are rejected.
 */
internal class ConnectionEpoch<T> {
    private var generation = 0L
    private var current: T? = null

    fun activate(value: T): Long {
        generation += 1
        current = value
        return generation
    }

    fun accepts(candidate: T): Boolean = current === candidate

    fun invalidate(candidate: T? = null) {
        if (candidate == null || current === candidate) {
            current = null
            generation += 1
        }
    }
}
