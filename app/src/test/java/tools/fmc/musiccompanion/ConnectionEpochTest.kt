package tools.fmc.musiccompanion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionEpochTest {
    @Test fun staleConnectionCallbackCannotSatisfyReplacementConnection() {
        val epoch = ConnectionEpoch<String>()
        val first = epoch.activate("first")

        epoch.activate("second")

        assertFalse(epoch.accepts("first"))
        assertTrue(epoch.accepts("second"))
        assertTrue(first > 0)
    }

    @Test fun invalidatingOldConnectionDoesNotClearReplacement() {
        val epoch = ConnectionEpoch<String>()
        epoch.activate("first")
        epoch.activate("second")

        epoch.invalidate("first")

        assertTrue(epoch.accepts("second"))
    }
}
