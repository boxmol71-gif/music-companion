package tools.fmc.musiccompanion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure protocol tests for notification-artwork normalization, encoding, and transfer framing. */
class ArtworkProtocolTest {
    @Test fun artworkRequestHasExactNonceAndGenerationLayout() {
        val request = parseMusicRequest(hex("4d31512a62785634120807060504030201"))
        assertNotNull(request)
        assertEquals(0x2a, request.id)
        assertEquals(MusicMethod.ARTWORK, request.method)
        assertEquals(12, request.args.size)

        val operation = musicOperation(request)
        assertIs<MusicOperation.Artwork>(operation)
        assertEquals(0x12345678L, operation.nonce)
        assertEquals(0x0102030405060708UL, operation.knownGeneration)

        val maximum = musicOperation(
            MusicRequest(1, MusicMethod.ARTWORK, ByteArray(12) { 0xFF.toByte() }),
        )
        assertIs<MusicOperation.Artwork>(maximum)
        assertEquals(0xFFFF_FFFFL, maximum.nonce)
        assertEquals(ULong.MAX_VALUE, maximum.knownGeneration)
    }

    @Test fun artworkRequestRejectsEveryWrongArgumentLength() {
        for (length in listOf(0, 1, 4, 11, 13, 16)) {
            assertNull(
                musicOperation(MusicRequest(1, MusicMethod.ARTWORK, ByteArray(length))),
                "accepted $length argument bytes",
            )
        }
        assertNull(musicOperation(MusicRequest(1, 0x64, ByteArray(12))))
    }

    @Test fun rgb332CodesAndContinuousPackingHaveGoldenValues() {
        assertEquals(0, rgb332Code(0, 0, 0))
        assertEquals(0x3F, rgb332Code(255, 255, 255))
        assertEquals(0x38, rgb332Code(255, 0, 0))
        assertEquals(0x06, rgb332Code(0, 255, 0))
        assertEquals(0x01, rgb332Code(0, 0, 255))
        assertEquals(0x39, rgb332Code(255, 0, 248))

        // 18 bits: 000001 000010 000011 -> 00000100 00100000 11.
        assertContentEquals(hex("0420c0"), packRgb332Codes(intArrayOf(1, 2, 3)))
        // 18 bits: 000000 111111 000001 -> 00000011 11110000 01000000.
        assertContentEquals(hex("03f040"), packRgb332Codes(intArrayOf(0, 63, 1)))
    }

    @Test fun rgb332PayloadIsExactlyThreeKilobytes() {
        val pixels = IntArray(ArtworkProtocol.WIDTH * ArtworkProtocol.HEIGHT) { index ->
            0xFF00_0000.toInt() or (index and 0xFF)
        }
        val payload = artworkRgb332Payload(pixels, ArtworkProtocol.WIDTH, ArtworkProtocol.HEIGHT)
        assertEquals(3072, payload.size)
        assertEquals(ArtworkProtocol.PAYLOAD_LENGTH, payload.size)
    }

    @Test fun crcAndGenerationHaveStableGoldenValues() {
        assertEquals(0xCBF43926L, artworkCrc32("123456789".toByteArray(Charsets.US_ASCII)))

        val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH)
        assertEquals(0x67E6C984L, artworkCrc32(payload))
        assertEquals(0x36E80D367F8763CEUL, artworkGeneration(payload))

        val changed = payload.copyOf()
        changed[0] = 1
        assertNotEquals(artworkGeneration(payload), artworkGeneration(changed))
    }

    @Test fun manifestAndChunksHaveExactWireSizes() {
        val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH) { (it and 0xFF).toByte() }
        val nonce = 0x12345678L
        val generation = artworkGeneration(payload)
        val manifest = artworkManifest(payload, nonce, generation)
        val chunks = artworkChunkRecords(payload, nonce)

        assertEquals(25, manifest.size)
        assertEquals(1, manifest.u8(0))
        assertEquals(1, manifest.u8(1))
        assertEquals(64, manifest.u8(2))
        assertEquals(64, manifest.u8(3))
        assertEquals(0, manifest.u8(4))
        assertContentEquals(hex("78563412"), manifest.copyOfRange(5, 9))
        assertEquals(generation, decodeArtworkManifest(manifest)?.generation)
        assertEquals(3072L, decodeArtworkManifest(manifest)?.payloadLength?.toLong())

        assertEquals(27, chunks.size)
        for (index in 1..26) {
            val chunk = decodeArtworkChunk(index, chunks[index - 1])
            assertNotNull(chunk)
            assertEquals(nonce, chunk.nonce)
            assertEquals((index - 1) * 115, chunk.offset)
            assertEquals(115, chunk.payload.size)
            assertEquals(121, chunks[index - 1].size)
        }
        val finalChunk = decodeArtworkChunk(27, chunks[26])
        assertNotNull(finalChunk)
        assertEquals(26 * 115, finalChunk.offset)
        assertEquals(82, finalChunk.payload.size)
        assertEquals(88, chunks[26].size)
    }

    @Test fun everyArtworkMessageFitsTheM1aLimit() {
        val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH)
        val records = listOf(artworkManifest(payload, 7)) + artworkChunkRecords(payload, 7)
        assertEquals(28, records.size)
        assertTrue(records.all { it.size <= RECORD_MAX })

        val messages = replies(9, RpcStatus.OK, records)
        assertEquals(28, messages.size)
        assertTrue(messages.all { it.size <= 128 })
        assertEquals(32, messages[0].size)
        assertEquals(128, messages[1].size)
        assertEquals(128, messages[26].size)
        assertEquals(95, messages[27].size)
        assertEquals(28, messages[0].u8(6))
        assertEquals(0, messages[0].u8(5))
        assertEquals(1, messages[1].u8(5))
        assertEquals(27, messages[27].u8(5))
    }

    @Test fun chunksReassembleTheExactPayloadAndRejectCorruption() {
        val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH) { (it * 31 and 0xFF).toByte() }
        val manifestRecord = artworkManifest(payload, 0xCAFEBABEL)
        val manifest = assertNotNull(decodeArtworkManifest(manifestRecord))
        val chunks = artworkChunkRecords(payload, 0xCAFEBABEL).mapIndexed { index, record ->
            assertNotNull(decodeArtworkChunk(index + 1, record))
        }
        assertContentEquals(payload, assertNotNull(reassembleArtwork(manifest, chunks)))

        val duplicate = chunks.toMutableList()
        duplicate[1] = duplicate[0]
        assertNull(reassembleArtwork(manifest, duplicate))

        val wrongNonce = chunks.toMutableList()
        wrongNonce[0] = wrongNonce[0].copy(nonce = wrongNonce[0].nonce xor 1L)
        assertNull(reassembleArtwork(manifest, wrongNonce))

        val badCrc = manifest.copy(crc32 = manifest.crc32 xor 1L)
        assertNull(reassembleArtwork(badCrc, chunks))
    }

    @Test fun transferSelectsUnchangedUnavailableAndCompleteResponses() {
        val payload = ByteArray(ArtworkProtocol.PAYLOAD_LENGTH)
        val generation = artworkGeneration(payload)

        assertIs<ArtworkTransfer.Unchanged>(artworkTransfer(payload, generation, 1))
        assertIs<ArtworkTransfer.Unavailable>(artworkTransfer(null, generation, 1))

        val available = assertIs<ArtworkTransfer.Available>(
            artworkTransfer(payload, generation xor 1UL, 1),
        )
        assertEquals(generation, available.generation)
        assertEquals(25, available.manifest.size)
        assertEquals(27, available.chunks.size)
        assertEquals(28, 1 + available.chunks.size)
    }

    @Test fun centerCropAndBilinearNormalizationUseOnlyPixels() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val pixels = intArrayOf(
            0, red, red, 0,
            0, blue, blue, 0,
        )
        val normalized = centerCropBilinearArgb(pixels, 4, 2)
        assertEquals(64 * 64, normalized.size)
        val center = normalized[32 * 64 + 32]
        val redValue = (center ushr 16) and 0xFF
        val blueValue = center and 0xFF
        assertTrue(redValue in 100..150, "red was $redValue")
        assertTrue(blueValue in 100..150, "blue was $blueValue")

        val transparentWhite = intArrayOf(0x00FFFFFF)
        val normalizedTransparent = centerCropBilinearArgb(transparentWhite, 1, 1)
        assertEquals(0xFF00_0000.toInt(), normalizedTransparent[0])
    }

    @Test fun trackRequestHasNoArgumentsAndRejectsExtraBytes() {
        val request = assertNotNull(parseMusicRequest(hex("4d31510863")))
        assertEquals(MusicMethod.TRACK, request.method)
        assertIs<MusicOperation.Track>(musicOperation(request))
        assertNull(musicOperation(MusicRequest(8, MusicMethod.TRACK, byteArrayOf(0))))
    }

    @Test fun titleAndArtistRecordsAreBoundedPrintableAscii() {
        val records = musicTrackRecords("  Midnight\tCity  ", "M83\u00E9")
        assertEquals(2, records.size)
        assertContentEquals(byteArrayOf(0, 13) + "Midnight City".toByteArray(Charsets.US_ASCII), records[0])
        assertContentEquals(byteArrayOf(1, 3) + "M83".toByteArray(Charsets.US_ASCII), records[1])
        assertTrue(records.all { it.size <= 62 })

        val long = musicTrackRecords("x".repeat(100), "y".repeat(100))
        assertEquals(TrackTextProtocol.TEXT_MAX + 2, long[0].size)
        assertEquals(TrackTextProtocol.TEXT_MAX + 2, long[1].size)
    }

    @Test fun trackReplyUsesTwoBoundedRecords() {
        val records = musicTrackRecords("Midnight City", "M83")
        val messages = replies(9, RpcStatus.OK, records)
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.size <= 128 })
        assertEquals(0, messages[0].u8(5))
        assertEquals(1, messages[1].u8(5))
        assertEquals(2, messages[0].u8(6))
    }

    @Test fun legacySnapshotRequestAndRecordRemainUnchanged() {
        val request = assertNotNull(parseMusicRequest(hex("4d3151086000")))
        assertIs<MusicOperation.Snapshot>(musicOperation(request))
        val records = musicSnapshotRecords(true, true, 7, 15)
        assertEquals(1, records.size)
        assertEquals(4, records.single().size)
        assertContentEquals(hex("0003070f"), records.single())
        val reply = replies(8, RpcStatus.OK, records).single()
        assertContentEquals(hex("4d3141080000010003070f"), reply)
        assertFalse(reply.contentEquals(hex("4d314108000028")))
    }
}
