package tools.fmc.musiccompanion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure protocol tests for the privacy-safe watch-facing music state. */
class MusicProtocolTest {
    @Test fun requestAndReplyFramingMatchTheWatchApp() {
        val snapshot = parseMusicRequest(hex("4d3151086000"))
        assertNotNull(snapshot)
        assertEquals(8, snapshot.id)
        assertEquals(MusicMethod.SNAPSHOT, snapshot.method)
        assertContentEquals(bytes(0), snapshot.args)
        assertIs<MusicOperation.Snapshot>(musicOperation(snapshot))

        val command = parseMusicRequest(hex("4d31510a610400"))
        assertNotNull(command)
        assertEquals(MusicMethod.COMMAND, command.method)
        val operation = musicOperation(command)
        assertIs<MusicOperation.Command>(operation)
        assertEquals(MusicCommand.NEXT, operation.command)
        assertEquals(0, operation.value)

        assertNull(parseMusicRequest(hex("52314105000000")))
        assertNull(parseMusicRequest(hex("4d3151")))
    }

    @Test fun operationValidationRejectsWrongLengthsAndCommands() {
        assertNull(musicOperation(MusicRequest(1, MusicMethod.SNAPSHOT, ByteArray(0))))
        assertNull(musicOperation(MusicRequest(1, MusicMethod.SNAPSHOT, bytes(0, 0))))
        assertNull(musicOperation(MusicRequest(1, MusicMethod.SNAPSHOT, bytes(1))))
        assertNull(musicOperation(MusicRequest(1, MusicMethod.COMMAND, bytes(MusicCommand.PLAY))))
        assertNull(musicOperation(MusicRequest(1, MusicMethod.COMMAND, bytes(8, 0))))
        assertNull(musicOperation(MusicRequest(1, 0x62, bytes(0))))
    }

    @Test fun everyCommandCodeIsAccepted() {
        for (command in 0..7) {
            val operation = musicOperation(MusicRequest(1, MusicMethod.COMMAND, bytes(command, 0)))
            assertIs<MusicOperation.Command>(operation)
            assertEquals(command, operation.command)
        }
    }

    @Test fun replyHeaderAndRecordLimitMatchTheWireFormat() {
        val record = hex("0003070f")
        val reply = replies(7, RpcStatus.OK, listOf(record)).single()
        assertContentEquals(hex("4d314107000001"), reply.copyOfRange(0, 7))
        assertContentEquals(record, reply.copyOfRange(7, reply.size))
        assertEquals(7, reply.size - record.size)

        val commandSuccess = replies(7, RpcStatus.OK).single()
        assertContentEquals(hex("4d314107000000"), commandSuccess)

        val error = replies(7, RpcStatus.BAD_REQUEST).single()
        assertContentEquals(hex("4d314107010000"), error)
    }

    @Test fun stateRecordContainsOnlyNonPersonalState() {
        assertEquals(
            "0003070f",
            musicStateRecord(true, true, 7, 15).hex(),
        )
        assertEquals("00000000", musicStateRecord(false, false, 0, 0).hex())

        val record = musicStateRecord(true, true, 7, 15)
        assertEquals(4, record.size)
        assertEquals(0, record.u8(0))
        assertEquals(3, record.u8(1))
        assertEquals(7, record.u8(2))
        assertEquals(15, record.u8(3))
    }

    @Test fun stateDecoderRejectsInvalidFields() {
        val state = decodeMusicState(hex("0003070f"))
        assertNotNull(state)
        assertTrue(state.playing)
        assertTrue(state.active)
        assertEquals(7, state.volume)
        assertEquals(15, state.maxVolume)

        assertNull(decodeMusicState(hex("0103070f"))) // wrong kind
        assertNull(decodeMusicState(hex("0008070f"))) // unknown flag
        assertNull(decodeMusicState(hex("00030f07"))) // volume above maximum
        assertNull(decodeMusicState(hex("0003070f00"))) // short record
        assertNull(decodeMusicState(hex("0003070f0000"))) // extra fields cannot carry text
    }

    @Test fun comparisonSeparatesPlaybackStateFromVolumeChanges() {
        val original = decodeMusicState(hex("0003070f"))!!
        val paused = decodeMusicState(hex("0002070f"))!!
        val louder = decodeMusicState(hex("0003080f"))!!

        assertFalse(original.samePlaybackState(paused))
        assertTrue(original.samePlaybackState(louder))
        assertTrue(original.sameData(original.copy()))
        assertFalse(original.sameData(louder))
    }

    @Test fun snapshotHasExactlyOneStateRecord() {
        val records = musicSnapshotRecords(true, true, 7, 15)
        assertEquals(1, records.size)
        assertEquals(4, records.single().size)
        assertEquals(0, records.single().u8(0))
        assertEquals(3, records.single().u8(1))

        val messages = replies(1, RpcStatus.OK, records)
        assertEquals(1, messages.size)
        assertEquals(1, messages.single().u8(6))
        assertEquals(7, messages.single().copyOfRange(0, 7).size)
    }

    @Test fun installedPackageNameBindsRequestsToTheControllerSlot() {
        val header = ByteArray(64)
        "WP2A".toByteArray().copyInto(header)
        "music-controller".toByteArray().copyInto(header, 8)
        assertEquals("music-controller", installedPackageName(bytes(Status.OK.code) + header))
        assertNull(installedPackageName(bytes(Status.BAD_REQUEST.code)))
        assertNull(installedPackageName(bytes(Status.OK.code)))
    }

    @Test fun malformedFrameLengthsAreIgnoredWithoutThrowing() {
        val malformedPlain = byteArrayOf(
            0xF5.toByte(), 0, 2, 0xFF.toByte(), 0xFF.toByte(), 0, 1, 0, 1, 0, 0, 0x48,
        )
        assertTrue(FrameParser().feed(malformedPlain).isEmpty())
    }

    @Test fun encryptedFramesRoundTripThroughTheBleEnvelope() {
        val key = ByteArray(16) { it.toByte() }
        val payload = hex("4d314107000000")
        val sealed = seal(key, payload)
        assertContentEquals(payload, open(key, sealed))

        val framed = frame(0x90A5, payload, key)
        val parsed = FrameParser().feed(framed).single()
        assertEquals(SYSTEM, parsed.c1)
        assertEquals(0x90A5, parsed.c2)
        assertContentEquals(payload, open(key, parsed.body))
    }
}
