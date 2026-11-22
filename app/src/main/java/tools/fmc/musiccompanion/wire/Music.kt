package tools.fmc.musiccompanion.wire

import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.floor
import kotlin.math.roundToInt

/** Watch-facing playback-state flags used by the fixed four-byte snapshot record. */
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

/** The fixed music-state snapshot: kind, flags, volume, and maximum volume. */
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

/** One fixed four-byte playback-state and music-volume snapshot record. */
fun musicSnapshotRecords(
    playing: Boolean,
    active: Boolean,
    volume: Int,
    maxVolume: Int,
): List<ByteArray> = listOf(musicStateRecord(playing, active, volume, maxVolume))

/** Decoded fixed music-state snapshot. */
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

/** Strictly decode the four-byte state snapshot record. */
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

/** Bounded title and artist records shown by the watch face. */
object TrackTextProtocol {
    const val TITLE_KIND = 0
    const val ARTIST_KIND = 1
    const val TEXT_MAX = 60
    const val RECORD_COUNT = 2
}

/** Converts display text to the printable-ASCII subset accepted by the watch. */
fun watchText(value: String): ByteArray {
    val clean = buildString {
        var previousSpace = false
        for (character in value) {
            val replacement = if (character.code in 0x20..0x7E) character else ' '
            if (replacement == ' ') {
                if (!previousSpace && isNotEmpty()) {
                    append(' ')
                    previousSpace = true
                }
            } else {
                append(replacement)
                previousSpace = false
            }
        }
    }.trim().take(TrackTextProtocol.TEXT_MAX)
    return clean.toByteArray(Charsets.US_ASCII)
}

/** Builds the title and artist records in their fixed index order. */
fun musicTrackRecords(title: String, artist: String): List<ByteArray> = listOf(
    watchTextRecord(TrackTextProtocol.TITLE_KIND, title),
    watchTextRecord(TrackTextProtocol.ARTIST_KIND, artist),
)

/** Builds one bounded printable-ASCII text record. */
fun watchTextRecord(kind: Int, value: String): ByteArray {
    require(kind == TrackTextProtocol.TITLE_KIND || kind == TrackTextProtocol.ARTIST_KIND)
    val bytes = watchText(value)
    return ByteArray(bytes.size + 2).also { record ->
        record[0] = kind.toByte()
        record[1] = bytes.size.toByte()
        bytes.copyInto(record, 2)
    }
}

/** Fixed-size wire format used for the optional low-resolution artwork transfer. */
object ArtworkProtocol {
    const val VERSION = 1
    const val ENCODING_RGB332 = 1
    const val WIDTH = 64
    const val HEIGHT = 64
    const val FLAGS = 0

    const val MANIFEST_SIZE = 25
    const val PAYLOAD_LENGTH = 3072
    const val CHUNK_COUNT = 27
    const val RECORD_COUNT = CHUNK_COUNT + 1
    const val CHUNK_PAYLOAD_LENGTH = 115
    const val FINAL_CHUNK_PAYLOAD_LENGTH = 82

    /** A chunk contains a four-byte nonce and a two-byte offset before its payload. */
    const val CHUNK_HEADER_SIZE = 6
}

/** Decoded artwork manifest fields. */
data class ArtworkManifest(
    val nonce: Long,
    val generation: ULong,
    val payloadLength: Int,
    val crc32: Long,
)

/** Decoded artwork chunk fields. The index is the `M1A` record index. */
data class ArtworkChunk(
    val index: Int,
    val nonce: Long,
    val offset: Int,
    val payload: ByteArray,
)

/** The result of preparing an artwork response. */
sealed interface ArtworkTransfer {
    data object Unchanged : ArtworkTransfer
    data object Unavailable : ArtworkTransfer
    data class Available(
        val generation: ULong,
        val manifest: ByteArray,
        val chunks: List<ByteArray>,
    ) : ArtworkTransfer
}

/**
 * Center-crops the largest source square, scales it to 64x64 with bilinear filtering, and returns opaque ARGB
 * pixels. Transparent source pixels are composited over black so no alpha reaches the RGB332 encoder.
 */
fun centerCropBilinearArgb(pixels: IntArray, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0)
    require(pixels.size.toLong() == width.toLong() * height.toLong())
    val side = minOf(width, height)
    val left = (width - side) / 2
    val top = (height - side) / 2
    val output = IntArray(ArtworkProtocol.WIDTH * ArtworkProtocol.HEIGHT)

    for (outputY in 0 until ArtworkProtocol.HEIGHT) {
        val sourceY = (outputY + 0.5) * side / ArtworkProtocol.HEIGHT - 0.5
        val y0 = floor(sourceY).toInt().coerceIn(0, side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        val fractionY = (sourceY - y0).coerceIn(0.0, 1.0)

        for (outputX in 0 until ArtworkProtocol.WIDTH) {
            val sourceX = (outputX + 0.5) * side / ArtworkProtocol.WIDTH - 0.5
            val x0 = floor(sourceX).toInt().coerceIn(0, side - 1)
            val x1 = (x0 + 1).coerceAtMost(side - 1)
            val fractionX = (sourceX - x0).coerceIn(0.0, 1.0)

            val topLeft = pixels[(top + y0) * width + left + x0]
            val topRight = pixels[(top + y0) * width + left + x1]
            val bottomLeft = pixels[(top + y1) * width + left + x0]
            val bottomRight = pixels[(top + y1) * width + left + x1]
            val topColor = blendArgb(topLeft, topRight, fractionX)
            val bottomColor = blendArgb(bottomLeft, bottomRight, fractionX)
            output[outputY * ArtworkProtocol.WIDTH + outputX] = blendArgb(topColor, bottomColor, fractionY)
        }
    }
    return output
}

/** Returns the six-bit RGB332-style code (3-bit red, 2-bit green, 1-bit blue). */
fun rgb332Code(red: Int, green: Int, blue: Int): Int {
    val redBits = (red and 0xFF) shr 5
    val greenBits = (green and 0xFF) shr 6
    val blueBits = (blue and 0xFF) shr 7
    return (redBits shl 3) or (greenBits shl 1) or blueBits
}

/** Packs six-bit codes continuously, most-significant bit first. */
fun packRgb332Codes(codes: IntArray): ByteArray {
    require(codes.all { it in 0..63 })
    val output = ByteArray((codes.size * 6 + 7) / 8)
    var outputIndex = 0
    var bitBuffer = 0
    var bitCount = 0
    for (code in codes) {
        bitBuffer = (bitBuffer shl 6) or code
        bitCount += 6
        while (bitCount >= 8) {
            bitCount -= 8
            output[outputIndex++] = ((bitBuffer shr bitCount) and 0xFF).toByte()
            bitBuffer = bitBuffer and ((1 shl bitCount) - 1)
        }
    }
    if (bitCount > 0) {
        output[outputIndex] = ((bitBuffer shl (8 - bitCount)) and 0xFF).toByte()
    }
    return output
}

/** Converts opaque or translucent ARGB pixels to a continuous RGB332 byte stream. */
fun packRgb332(pixels: IntArray): ByteArray {
    val codes = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        rgb332Code((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)
    }
    return packRgb332Codes(codes)
}

/** Normalizes ARGB pixels and creates the exact 3,072-byte artwork payload. */
fun artworkRgb332Payload(pixels: IntArray, width: Int, height: Int): ByteArray {
    require(width * height == pixels.size)
    val normalized = centerCropBilinearArgb(pixels, width, height)
    return packRgb332(normalized).also {
        require(it.size == ArtworkProtocol.PAYLOAD_LENGTH)
    }
}

/** CRC-32/ISO-HDLC, matching `java.util.zip.CRC32`. */
fun artworkCrc32(payload: ByteArray): Long = CRC32().apply { update(payload) }.value

/**
 * Computes the content generation from `SHA-256("M1ART1" || 0 || width_le16 || height_le16 || encoding || payload)`.
 * The first eight digest bytes are interpreted as a little-endian unsigned 64-bit value; zero is replaced by one.
 */
fun artworkGeneration(payload: ByteArray): ULong {
    require(payload.size == ArtworkProtocol.PAYLOAD_LENGTH)
    val digest = MessageDigest.getInstance("SHA-256").run {
        update("M1ART1".toByteArray(Charsets.US_ASCII))
        update(0)
        update(byteArrayOf(
            (ArtworkProtocol.WIDTH and 0xFF).toByte(),
            (ArtworkProtocol.WIDTH ushr 8).toByte(),
            (ArtworkProtocol.HEIGHT and 0xFF).toByte(),
            (ArtworkProtocol.HEIGHT ushr 8).toByte(),
            ArtworkProtocol.ENCODING_RGB332.toByte(),
        ))
        update(payload)
        digest()
    }
    var generation = 0UL
    for (index in 7 downTo 0) {
        generation = (generation shl 8) or digest[index].toUByte().toULong()
    }
    return if (generation == 0UL) 1UL else generation
}

/** Builds the exact 25-byte artwork manifest. */
fun artworkManifest(
    payload: ByteArray,
    nonce: Long,
    generation: ULong = artworkGeneration(payload),
): ByteArray {
    require(payload.size == ArtworkProtocol.PAYLOAD_LENGTH)
    require(nonce in 0..0xFFFF_FFFFL)
    require(generation != 0UL)
    return ByteArray(ArtworkProtocol.MANIFEST_SIZE).also { manifest ->
        manifest[0] = ArtworkProtocol.VERSION.toByte()
        manifest[1] = ArtworkProtocol.ENCODING_RGB332.toByte()
        manifest[2] = ArtworkProtocol.WIDTH.toByte()
        manifest[3] = ArtworkProtocol.HEIGHT.toByte()
        manifest[4] = ArtworkProtocol.FLAGS.toByte()
        manifest.writeU32Le(5, nonce)
        manifest.writeU64Le(9, generation)
        manifest.writeU32Le(17, payload.size.toLong())
        manifest.writeU32Le(21, artworkCrc32(payload))
    }
}

/** Strictly decodes an artwork manifest. */
fun decodeArtworkManifest(record: ByteArray): ArtworkManifest? {
    if (record.size != ArtworkProtocol.MANIFEST_SIZE) return null
    if (record.u8(0) != ArtworkProtocol.VERSION) return null
    if (record.u8(1) != ArtworkProtocol.ENCODING_RGB332) return null
    if (record.u8(2) != ArtworkProtocol.WIDTH || record.u8(3) != ArtworkProtocol.HEIGHT) return null
    if (record.u8(4) != ArtworkProtocol.FLAGS) return null
    val generation = record.readU64Le(9)
    val payloadLength = record.readU32Le(17)
    if (generation == 0UL || payloadLength != ArtworkProtocol.PAYLOAD_LENGTH.toLong()) return null
    return ArtworkManifest(
        nonce = record.readU32Le(5),
        generation = generation,
        payloadLength = payloadLength.toInt(),
        crc32 = record.readU32Le(21),
    )
}

/** Builds the 27 fixed artwork chunk records. */
fun artworkChunkRecords(payload: ByteArray, nonce: Long): List<ByteArray> {
    require(payload.size == ArtworkProtocol.PAYLOAD_LENGTH)
    require(nonce in 0..0xFFFF_FFFFL)
    return (1..ArtworkProtocol.CHUNK_COUNT).map { index ->
        val offset = (index - 1) * ArtworkProtocol.CHUNK_PAYLOAD_LENGTH
        val length = minOf(ArtworkProtocol.CHUNK_PAYLOAD_LENGTH, payload.size - offset)
        ByteArray(ArtworkProtocol.CHUNK_HEADER_SIZE + length).also { chunk ->
            chunk.writeU32Le(0, nonce)
            chunk.writeU16Le(4, offset)
            payload.copyInto(chunk, ArtworkProtocol.CHUNK_HEADER_SIZE, offset, offset + length)
        }
    }
}

/** Strictly decodes and validates one artwork chunk record. */
fun decodeArtworkChunk(index: Int, record: ByteArray): ArtworkChunk? {
    if (index !in 1..ArtworkProtocol.CHUNK_COUNT) return null
    val expectedOffset = (index - 1) * ArtworkProtocol.CHUNK_PAYLOAD_LENGTH
    val expectedLength = minOf(ArtworkProtocol.CHUNK_PAYLOAD_LENGTH, ArtworkProtocol.PAYLOAD_LENGTH - expectedOffset)
    if (record.size != ArtworkProtocol.CHUNK_HEADER_SIZE + expectedLength) return null
    val nonce = record.readU32Le(0)
    val offset = record.readU16Le(4)
    if (offset != expectedOffset) return null
    return ArtworkChunk(
        index = index,
        nonce = nonce,
        offset = offset,
        payload = record.copyOfRange(ArtworkProtocol.CHUNK_HEADER_SIZE, record.size),
    )
}

/** Reassembles and CRC-validates artwork chunks. */
fun reassembleArtwork(manifest: ArtworkManifest, chunks: List<ArtworkChunk>): ByteArray? {
    if (manifest.payloadLength != ArtworkProtocol.PAYLOAD_LENGTH || manifest.generation == 0UL) return null
    if (chunks.size != ArtworkProtocol.CHUNK_COUNT) return null
    val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH)
    val seen = BooleanArray(ArtworkProtocol.RECORD_COUNT)
    for (chunk in chunks) {
        if (chunk.index !in 1..ArtworkProtocol.CHUNK_COUNT || seen[chunk.index]) return null
        val expectedOffset = (chunk.index - 1) * ArtworkProtocol.CHUNK_PAYLOAD_LENGTH
        val expectedLength = minOf(
            ArtworkProtocol.CHUNK_PAYLOAD_LENGTH,
            ArtworkProtocol.PAYLOAD_LENGTH - expectedOffset,
        )
        if (chunk.nonce != manifest.nonce || chunk.offset != expectedOffset) return null
        if (chunk.payload.size != expectedLength) return null
        chunk.payload.copyInto(payload, expectedOffset)
        seen[chunk.index] = true
    }
    if (seen.drop(1).any { !it }) return null
    if (artworkCrc32(payload) != manifest.crc32) return null
    return payload
}

/**
 * Chooses the response shape for the current artwork. A null payload means that no matching artwork exists.
 */
fun artworkTransfer(
    payload: ByteArray?,
    knownGeneration: ULong,
    nonce: Long,
): ArtworkTransfer {
    if (payload == null) return ArtworkTransfer.Unavailable
    val generation = artworkGeneration(payload)
    if (generation == knownGeneration) return ArtworkTransfer.Unchanged
    return ArtworkTransfer.Available(
        generation = generation,
        manifest = artworkManifest(payload, nonce, generation),
        chunks = artworkChunkRecords(payload, nonce),
    )
}

private fun blendArgb(first: Int, second: Int, fraction: Double): Int {
    val firstColor = argbOverBlack(first)
    val secondColor = argbOverBlack(second)
    fun channel(shift: Int): Int {
        val a = (firstColor ushr shift) and 0xFF
        val b = (secondColor ushr shift) and 0xFF
        return (a + (b - a) * fraction).roundToInt().coerceIn(0, 255)
    }
    return 0xFF00_0000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun argbOverBlack(pixel: Int): Int {
    val alpha = (pixel ushr 24) and 0xFF
    val red = ((pixel ushr 16) and 0xFF) * alpha / 255
    val green = ((pixel ushr 8) and 0xFF) * alpha / 255
    val blue = (pixel and 0xFF) * alpha / 255
    return (red shl 16) or (green shl 8) or blue
}

private fun ByteArray.writeU16Le(offset: Int, value: Int) {
    require(value in 0..0xFFFF)
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.readU16Le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.writeU32Le(offset: Int, value: Long) {
    require(value in 0..0xFFFF_FFFFL)
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun ByteArray.writeU64Le(offset: Int, value: ULong) {
    for (index in 0 until 8) {
        this[offset + index] = ((value shr (index * 8)) and 0xFFUL).toByte()
    }
}

private fun ByteArray.readU32Le(offset: Int): Long =
    (u8(offset).toLong()) or
        (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or
        (u8(offset + 3).toLong() shl 24)

private fun ByteArray.readU64Le(offset: Int): ULong {
    var value = 0UL
    for (index in 7 downTo 0) {
        value = (value shl 8) or u8(offset + index).toULong()
    }
    return value
}
