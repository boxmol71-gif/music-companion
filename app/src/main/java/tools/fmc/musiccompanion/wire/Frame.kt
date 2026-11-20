package tools.fmc.musiccompanion.wire

import java.security.GeneralSecurityException
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SYSTEM = 0xFFFF
private val IV = bytes(0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x5A)
private val plainReplies = setOf(0x0048)

fun crc32(d: ByteArray): Long = CRC32().apply { update(d) }.value

private fun aes(mode: Int, key: ByteArray, input: ByteArray): ByteArray? {
    if (key.size != 16) return null
    return try {
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
            doFinal(input)
        }
    } catch (_: GeneralSecurityException) {
        null
    }
}

fun seal(key: ByteArray, plain: ByteArray): ByteArray =
    requireNotNull(aes(Cipher.ENCRYPT_MODE, key, plain + buf { le32(crc32(plain)) }))

fun open(key: ByteArray, cipher: ByteArray): ByteArray? {
    if (cipher.isEmpty() || cipher.size % 16 != 0) return null
    val body = aes(Cipher.DECRYPT_MODE, key, cipher) ?: return null
    if (body.size < 4) return null
    val plain = body.copyOf(body.size - 4)
    return if (crc32(plain) == body.le32(body.size - 4)) plain else null
}

fun channelKey(secretHex: String): ByteArray? = if (secretHex.length == 32) hexOrNull(secretHex) else null

fun frame(c2: Int, payload: ByteArray, key: ByteArray?): ByteArray {
    val body = if (key != null) seal(key, payload) else payload + buf { le32(crc32(payload)) }
    return buf { u8(0xF5); be16(body.size); be16(SYSTEM); be16(1); be16(1); be16(c2); add(body) }
}

class RawFrame(val c1: Int, val c2: Int, val body: ByteArray)

class FrameParser {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<RawFrame> {
        buf += data
        val out = mutableListOf<RawFrame>()
        while (true) {
            val start = buf.indexOf(0xF5.toByte())
            if (start < 0) {
                buf = ByteArray(0)
                break
            }
            buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 11) break
            val length = buf.be16(1)
            val c1 = buf.be16(3)
            val c2 = buf.be16(9)
            if (length > MAX_BODY_BYTES || (c1 == SYSTEM && c2 in plainReplies && length < 4)) {
                // The length is untrusted. Drop the malformed header instead of allocating or slicing
                // with a negative length.
                buf = ByteArray(0)
                break
            }
            val size = if (c1 == SYSTEM && c2 in plainReplies) length - 4 else length
            if (buf.size < 11 + size) {
                if (buf.size > MAX_BODY_BYTES + 11) buf = ByteArray(0)
                break
            }
            out += RawFrame(c1, c2, buf.copyOfRange(11, 11 + size))
            buf = buf.copyOfRange(11 + size, buf.size)
        }
        return out
    }

    private companion object {
        const val MAX_BODY_BYTES = 4_096
    }
}
