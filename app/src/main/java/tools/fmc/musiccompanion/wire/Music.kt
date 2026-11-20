package tools.fmc.musiccompanion.wire

/** Privacy-safe watch-facing music state with no personal field types. */
object MusicProtocol {
    const val RESPONSE_PLAYING = 0x01
    const val RESPONSE_ACTIVE = 0x02
    const val STATE_KIND = 0
    const val STATE_RECORD_SIZE = 4
}

/** Every playback and music-volume command understood by the phone. */
object MusicCommand {
    const val PLAY = 0
    const val PAUSE = 1
    const val TOGGLE = 2
    const val PREVIOUS = 3
    const val NEXT = 4
    const val VOLUME_UP = 5
    const val VOLUME_DOWN = 6
    const val SET_VOLUME = 7

    fun isValid(command: Int): Boolean = command in PLAY..SET_VOLUME
}

/** The only music state sent to the watch: kind, flags, volume, and maximum volume. */
fun musicStateRecord(
    playing: Boolean,
    active: Boolean,
    volume: Int,
    maxVolume: Int,
): ByteArray {
    val flags = (if (playing) MusicProtocol.RESPONSE_PLAYING else 0) or
        (if (active) MusicProtocol.RESPONSE_ACTIVE else 0)
    return buf {
        u8(MusicProtocol.STATE_KIND)
        u8(flags)
        u8(volume.coerceIn(0, 255))
        u8(maxVolume.coerceIn(0, 255))
    }
}

/** One snapshot record containing only non-personal playback state and music volume. */
fun musicSnapshotRecords(
    playing: Boolean,
    active: Boolean,
    volume: Int,
    maxVolume: Int,
): List<ByteArray> = listOf(musicStateRecord(playing, active, volume, maxVolume))

/** Decoded non-personal music state. */
data class MusicState(
    val flags: Int,
    val volume: Int,
    val maxVolume: Int,
) {
    val playing: Boolean get() = flags and MusicProtocol.RESPONSE_PLAYING != 0
    val active: Boolean get() = flags and MusicProtocol.RESPONSE_ACTIVE != 0

    /** Whether the playback state itself is equal, ignoring volume. */
    fun samePlaybackState(other: MusicState): Boolean = flags == other.flags

    /** Whether all watch-facing state is exactly equal. */
    fun sameData(other: MusicState): Boolean =
        flags == other.flags && volume == other.volume && maxVolume == other.maxVolume
}

/** Strictly decode the four-byte non-personal state record. */
fun decodeMusicState(record: ByteArray): MusicState? {
    if (record.size != MusicProtocol.STATE_RECORD_SIZE) return null
    if (record.u8(0) != MusicProtocol.STATE_KIND) return null
    val flags = record.u8(1)
    val volume = record.u8(2)
    val maxVolume = record.u8(3)
    val knownFlags = MusicProtocol.RESPONSE_PLAYING or MusicProtocol.RESPONSE_ACTIVE
    if ((flags and knownFlags) != flags) return null
    if (volume > maxVolume) return null
    return MusicState(flags, volume, maxVolume)
}
