package tools.fmc.musiccompanion.wire

object MusicMethod {
    const val SNAPSHOT = 0x60
    const val COMMAND = 0x61
}

const val MUSIC_PACKAGE_NAME = "music-controller"

object RpcStatus {
    const val OK = 0
    const val BAD_REQUEST = 1
    const val DENIED = 2
    const val UNAVAILABLE = 3
    const val ASK = 4
}

class RpcError(val status: Int) : Exception("rpc status $status")

data class MusicRequest(val id: Int, val method: Int?, val args: ByteArray)

/** Parses the standalone bridge's `M1Q` namespace. */
fun parseMusicRequest(message: ByteArray): MusicRequest? {
    if (message.size < 5 || message[0] != 'M'.code.toByte() || message[1] != '1'.code.toByte() ||
        message[2] != 'Q'.code.toByte()) return null
    return MusicRequest(message.u8(3), message.u8(4), message.copyOfRange(5, message.size))
}

/** A validated operation carried by an `M1Q` request. */
sealed interface MusicOperation {
    /** The reserved request byte is ignored; the answer contains only non-personal playback state. */
    data object Snapshot : MusicOperation
    data class Command(val command: Int, val value: Int) : MusicOperation
}

/** Null for an unknown method or arguments that do not match the request layout. */
fun musicOperation(request: MusicRequest): MusicOperation? = when (request.method) {
    MusicMethod.SNAPSHOT -> request.args.takeIf { it.size == 1 && it.u8(0) == 0 }
        ?.let { MusicOperation.Snapshot }
    MusicMethod.COMMAND -> request.args.takeIf { it.size == 2 }
        ?.let { MusicOperation.Command(it.u8(0), it.u8(1)) }
        ?.takeIf { MusicCommand.isValid(it.command) }
    else -> null
}

/** The package name from an INFO reply, used to bind requests to the installed Music Controller slot. */
fun installedPackageName(reply: ByteArray): String? {
    if (reply.isEmpty() || reply.u8(0) != Status.OK.code) return null
    val header = reply.copyOfRange(1, reply.size)
    if (header.size < 64 || !header.copyOf(4).contentEquals("WP2A".toByteArray())) return null
    val raw = header.copyOfRange(8, 32).takeWhile { it != 0.toByte() }.toByteArray()
    if (raw.isEmpty()) return null
    return String(raw, Charsets.UTF_8)
}

const val RECORD_MAX = 121

fun replies(id: Int, status: Int, records: List<ByteArray> = emptyList()): List<ByteArray> {
    require(records.size <= 255)
    require(records.all { it.size <= RECORD_MAX })
    fun msg(index: Int, total: Int, record: ByteArray) =
        buf { add("M1A".toByteArray()); u8(id); u8(status); u8(index); u8(total); add(record) }
    if (status != RpcStatus.OK || records.isEmpty()) return listOf(msg(0, 0, ByteArray(0)))
    return records.mapIndexed { i, record -> msg(i, records.size, record) }
}
