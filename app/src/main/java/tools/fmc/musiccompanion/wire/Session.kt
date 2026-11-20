package tools.fmc.musiccompanion.wire

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

object Uuid {
    const val SHELL_WRITE = "77D4FF01-2FE2-2334-0D35-9CCD078F529C"
    const val SHELL_NOTIFY = "77D4FF02-2FE2-2334-0D35-9CCD078F529C"
    const val CMD_WRITE = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val CMD_NOTIFY = "0000FFF1-0000-1000-8000-00805F9B34FB"
}

interface Link {
    suspend fun write(uuid: String, data: ByteArray)
    fun onNotify(handler: (String, ByteArray) -> Unit)
}

object Cmd {
    const val INFO = 0x90A0
    const val MSG = 0x90A5
    const val PING = 0x90A7
    const val EVENT = 0xB0A6
}

enum class Status { OK, BAD_REQUEST, IO, SEQUENCE, CRC, BUSY, INVALID, NO_APP, FULL, PAUSED;
    val code get() = ordinal
}

sealed class WireError(message: String) : Exception(message) {
    data class Timeout(val what: String) : WireError(what)
    data object NoApp : WireError("no app running on the watch")
    data class Refused(val status: Int) : WireError("refused (%02X)".format(status))
}

fun interface Requester {
    suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray
}

suspend fun Requester.call(c2: Int, payload: ByteArray) = call(c2, payload, 6000)

internal fun <T> Queue<T>.drain() {
    while (tryReceive().isSuccess) {}
}

internal suspend fun <T> Queue<T>.next(timeoutMs: Long): T? =
    if (timeoutMs <= 0) tryReceive().getOrNull() else withTimeoutOrNull(timeoutMs) { receive() }

/** Monotonic milliseconds; wall-clock changes cannot extend a transport deadline. */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

class KSession private constructor(private val link: Link, val key: ByteArray, var pollMs: Long?) : Requester {
    private val frames = Queue<Pair<Int, ByteArray>>(QUEUE_LIMIT, BufferOverflow.DROP_OLDEST)
    private val events = Queue<ByteArray>(QUEUE_LIMIT, BufferOverflow.DROP_OLDEST)
    private val parser = FrameParser()

    companion object {
        private const val QUEUE_LIMIT = 256
        private const val ANY_SLOT = 0xFF
        private const val DEFAULT_POLL_MS = 250L
        private const val DEFAULT_SEND_TIMEOUT_MS = 5_000L

        suspend fun open(
            link: Link,
            secretHex: String? = null,
            pollMs: Long? = DEFAULT_POLL_MS,
            register: Boolean = true,
        ): Pair<KSession, String> {
            val shell = Queue<ByteArray>(QUEUE_LIMIT, BufferOverflow.DROP_OLDEST)
            val session = AtomicReference<KSession?>()
            link.onNotify { uuid, value ->
                when (uuid.uppercase()) {
                    Uuid.SHELL_NOTIFY -> shell.trySend(value)
                    Uuid.CMD_NOTIFY -> session.get()?.feed(value)
                }
            }
            var secret = secretHex
            var tries = 0
            while (secret == null) {
                tries++
                link.write(Uuid.SHELL_WRITE, "AT GETSECRET\r\n".toByteArray())
                var reply = ""
                while (listOf("OK", "ERROR", "FAIL").none { reply.contains(it) }) {
                    val d = shell.next(6000) ?: break
                    reply += String(d)
                }
                val text = reply.trim()
                if (text.startsWith("GETSECRET:") && text.endsWith(",OK")) {
                    secret = text.removePrefix("GETSECRET:").removeSuffix(",OK")
                } else if (tries == 3) {
                    throw WireError.Timeout("no pairing secret from the watch shell")
                }
            }
            val key = channelKey(secret) ?: throw WireError.Refused(0xFF)
            val sessionValue = KSession(link, key, pollMs)
            session.set(sessionValue)
            if (register) sessionValue.ping()
            return sessionValue to secret
        }
    }

    @Synchronized
    private fun feed(value: ByteArray) {
        for (f in parser.feed(value)) {
            if (f.c1 != SYSTEM) continue
            if (f.c2 == Cmd.EVENT) events.trySend(f.body) else frames.trySend(f.c2 to f.body)
        }
    }

    private suspend fun request(c2: Int, payload: ByteArray, timeoutMs: Long = 6000): ByteArray {
        if (timeoutMs <= 0) throw WireError.Timeout("no reply to %04X".format(c2))
        frames.drain()
        val deadline = monotonicMs() + timeoutMs
        try {
            withTimeout(timeoutMs) {
                link.write(Uuid.CMD_WRITE, frame(c2, payload, key))
            }
        } catch (timeout: TimeoutCancellationException) {
            throw WireError.Timeout("write of %04X timed out".format(c2))
        }
        while (true) {
            val left = deadline - monotonicMs()
            val (got, body) = (if (left > 0) frames.next(left) else null)
                ?: throw WireError.Timeout("no reply to %04X".format(c2))
            if (got == c2 or 0xA000) open(key, body)?.let { return it }
        }
    }

    suspend fun ping(timeoutMs: Long = 6000): Pair<Int, Int> {
        val r = request(Cmd.PING, bytes(0), timeoutMs)
        if (r.size != 3 || r.u8(0) != Status.OK.code) throw WireError.Refused(r.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF)
        return r.u8(1) to r.u8(2)
    }

    /** Sends one reply to the app slot that made the request. */
    suspend fun send(data: ByteArray, slot: Int = ANY_SLOT, timeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS) {
        require(slot in 0..7 || slot == ANY_SLOT) { "invalid app slot" }
        val deadline = monotonicMs() + timeoutMs
        var delayMs = 20L
        while (true) {
            val left = deadline - monotonicMs()
            if (left <= 0) throw WireError.Timeout("reply was not delivered")
            val r = request(Cmd.MSG, bytes(slot) + data, left)
            val status = if (r.isEmpty()) 0xFF else r.u8(0)
            when (status) {
                Status.OK.code -> return
                Status.NO_APP.code -> throw WireError.NoApp
                Status.FULL.code, Status.PAUSED.code -> {
                    if (monotonicMs() + delayMs >= deadline) throw WireError.Refused(status)
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, 500)
                }
                else -> throw WireError.Refused(status)
            }
        }
    }

    suspend fun recvFrom(timeoutMs: Long): Pair<Int, ByteArray> {
        val deadline = monotonicMs() + timeoutMs
        while (true) {
            val left = deadline - monotonicMs()
            if (left <= 0) throw WireError.Timeout("no message")
            val body = events.next(pollMs?.let { minOf(it, left) } ?: left)
            if (body != null) {
                val plain = open(key, body)
                if (plain != null && plain.size >= 3) return plain.u8(1) to plain.copyOfRange(2, plain.size)
                continue
            }
            val poll = pollMs
            if (poll != null) {
                val remaining = deadline - monotonicMs()
                if (remaining <= 0) throw WireError.Timeout("no message")
                val pingTimeout = poll.coerceAtMost(remaining)
                ping(pingTimeout)
            }
        }
    }

    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long) = request(c2, payload, timeoutMs)
}
