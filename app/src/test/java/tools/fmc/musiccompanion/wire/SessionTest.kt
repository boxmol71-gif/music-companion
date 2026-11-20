package tools.fmc.musiccompanion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Transport tests for polling, source-slot routing and bounded reply time. */
class SessionTest {
    private class FakeLink(
        private val key: ByteArray,
        private val respond: Boolean = true,
        private val writeDelayMs: Long = 0,
    ) : Link {
        val requests = mutableListOf<ByteArray>()
        private val handlers = mutableListOf<(String, ByteArray) -> Unit>()

        override suspend fun write(uuid: String, data: ByteArray) {
            if (writeDelayMs > 0) delay(writeDelayMs)
            val raw = FrameParser().feed(data).single()
            val request = requireNotNull(open(key, raw.body))
            requests += request
            if (!respond) return

            when (raw.c2) {
                Cmd.PING -> {
                    notify(frame(raw.c2 or 0xA000, bytes(Status.OK.code, 5, 1), key))
                    notify(frame(Cmd.EVENT, bytes(9, 5, 0x4D, 0x31), key))
                }
                Cmd.MSG -> notify(frame(raw.c2 or 0xA000, bytes(Status.OK.code), key))
                else -> notify(frame(raw.c2 or 0xA000, bytes(Status.OK.code), key))
            }
        }

        override fun onNotify(handler: (String, ByteArray) -> Unit) {
            handlers += handler
        }

        private fun notify(data: ByteArray) {
            handlers.forEach { it(Uuid.CMD_NOTIFY, data) }
        }
    }

    @Test fun pollingDeliversQueuedAppEventsWithoutAMinuteDelay() = runBlocking {
        val link = FakeLink(ByteArray(16) { it.toByte() })
        val (session, _) = KSession.open(link, "000102030405060708090a0b0c0d0e0f", register = false)

        val received = session.recvFrom(1_500)

        assertEquals(5, received.first)
        assertContentEquals(bytes(0x4D, 0x31), received.second)
        assertTrue(link.requests.any { it.size == 1 && it.u8(0) == 0 })
    }

    @Test fun repliesAreAddressedToTheRequestingAppSlot() = runBlocking {
        val link = FakeLink(ByteArray(16) { it.toByte() })
        val (session, _) = KSession.open(link, "000102030405060708090a0b0c0d0e0f", register = false)

        session.send(bytes(0x4D, 0x31), slot = 3)

        val message = link.requests.last()
        assertEquals(3, message.u8(0))
        assertContentEquals(bytes(0x4D, 0x31), message.copyOfRange(1, message.size))
    }

    @Test fun replyDeliveryHonoursTheCallersDeadline() = runBlocking {
        val link = FakeLink(ByteArray(16) { it.toByte() }, respond = false)
        val (session, _) = KSession.open(link, "000102030405060708090a0b0c0d0e0f", register = false)

        assertFailsWith<WireError.Timeout> {
            session.send(bytes(0x4D, 0x31), slot = 3, timeoutMs = 100)
        }
        Unit
    }

    @Test fun receiveDeadlineIncludesTheKeepalivePing() = runBlocking {
        val link = FakeLink(ByteArray(16) { it.toByte() }, respond = false)
        val (session, _) = KSession.open(link, "000102030405060708090a0b0c0d0e0f", register = false)
        val started = monotonicMs()

        assertFailsWith<WireError.Timeout> {
            session.recvFrom(300)
        }
        assertTrue(monotonicMs() - started < 1_000, "the keepalive ping exceeded the receive deadline")
    }

    @Test fun writeTimeCountsAgainstTheReplyDeadline() = runBlocking {
        val link = FakeLink(
            ByteArray(16) { it.toByte() },
            respond = false,
            writeDelayMs = 500,
        )
        val (session, _) = KSession.open(link, "000102030405060708090a0b0c0d0e0f", register = false)
        val started = monotonicMs()

        assertFailsWith<WireError.Timeout> {
            session.send(bytes(0x4D, 0x31), slot = 3, timeoutMs = 100)
        }
        assertTrue(monotonicMs() - started < 450, "the write exceeded the caller's deadline")
    }
}
