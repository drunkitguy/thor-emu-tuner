package dev.thoremutuner.core.scan

/**
 * Random-access read-only bytes. A short (or empty) result means end of file.
 * The app implements this over a ParcelFileDescriptor channel; tests over a ByteArray.
 */
interface ByteSource : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    override fun close() {}
}

/** Reads exactly [length] bytes or returns null (EOF / short read). */
fun ByteSource.readExact(offset: Long, length: Int): ByteArray? {
    if (offset < 0 || length < 0) return null
    val b = read(offset, length)
    return if (b.size == length) b else null
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || offset < 0 || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

class ReadBudgetExceeded(val budget: Long) : RuntimeException("read budget of $budget bytes exceeded")

/**
 * Counts bytes read and enforces the per-file read budget (PLAN section 6.1): 256 KiB plus up to 64
 * ISO9660 directory sectors. Probes therefore never read a whole ROM.
 */
class BudgetedByteSource(
    private val inner: ByteSource,
    val budget: Long = READ_BUDGET,
) : ByteSource {
    var bytesRead: Long = 0
        private set
    var reads: Int = 0
        private set

    override val size: Long get() = inner.size

    override fun read(offset: Long, length: Int): ByteArray {
        if (bytesRead + length > budget) throw ReadBudgetExceeded(budget)
        val b = inner.read(offset, length)
        bytesRead += b.size
        reads++
        return b
    }

    override fun close() = inner.close()

    companion object {
        /** 256 KiB + 64 raw (2352-byte) directory sectors. */
        const val READ_BUDGET: Long = 256L * 1024 + 64L * 2352
    }
}

internal fun ByteArray.u8(off: Int): Int = this[off].toInt() and 0xFF
internal fun ByteArray.u16le(off: Int): Int = u8(off) or (u8(off + 1) shl 8)
internal fun ByteArray.u32le(off: Int): Long =
    (u8(off).toLong()) or (u8(off + 1).toLong() shl 8) or (u8(off + 2).toLong() shl 16) or (u8(off + 3).toLong() shl 24)
internal fun ByteArray.u32be(off: Int): Long =
    (u8(off).toLong() shl 24) or (u8(off + 1).toLong() shl 16) or (u8(off + 2).toLong() shl 8) or u8(off + 3).toLong()
internal fun ByteArray.u64le(off: Int): Long = u32le(off) or (u32le(off + 4) shl 32)
internal fun ByteArray.u64be(off: Int): Long = (u32be(off) shl 32) or u32be(off + 4)

/** ASCII/UTF-8 text from [off], [len] bytes, cut at the first NUL, trimmed. */
internal fun ByteArray.cString(off: Int, len: Int): String {
    val end = (off until minOf(size, off + len)).firstOrNull { this[it] == 0.toByte() } ?: minOf(size, off + len)
    return String(this, off, end - off, Charsets.UTF_8).trim()
}

internal fun ByteArray.startsWithAt(off: Int, magic: ByteArray): Boolean {
    if (off < 0 || off + magic.size > size) return false
    for (i in magic.indices) if (this[off + i] != magic[i]) return false
    return true
}

internal fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
