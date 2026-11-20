package tools.fmc.musiccompanion.wire

import java.io.ByteArrayOutputStream

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.le32(i: Int): Long = (le16(i).toLong() or (le16(i + 2).toLong() shl 16))
fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun hexOrNull(s: String): ByteArray? {
    if (s.length % 2 != 0) return null
    return ByteArray(s.length / 2) { i ->
        val hi = Character.digit(s[2 * i], 16)
        val lo = Character.digit(s[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        ((hi shl 4) or lo).toByte()
    }
}

fun hex(s: String): ByteArray = requireNotNull(hexOrNull(s)) { "bad hex" }
fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

class Buf {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v) }
    fun le16(v: Int) = apply { out.write(v); out.write(v shr 8) }
    fun le32(v: Long) = apply { le16(v.toInt()); le16((v shr 16).toInt()) }
    fun be16(v: Int) = apply { out.write(v shr 8); out.write(v) }
    fun add(b: ByteArray) = apply { out.write(b) }
    fun build(): ByteArray = out.toByteArray()
}

fun buf(block: Buf.() -> Unit): ByteArray = Buf().apply(block).build()
fun sha256Hex(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).hex()
