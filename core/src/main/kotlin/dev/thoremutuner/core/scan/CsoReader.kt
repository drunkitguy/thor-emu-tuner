package dev.thoremutuner.core.scan

import java.util.zip.Inflater

/**
 * PSP CSO ("CISO" with header_size 0x18) as a [SectorReader], following PPSSPP BlockDevices.cpp:
 * total u64 @8, blockSize u32 @0x10, align u8 @0x15, index u32[n+1] @0x18. Index bit 31 marks a
 * stored (plain) block; other blocks are raw deflate.
 */
class CsoReader private constructor(
    private val src: ByteSource,
    private val totalBytes: Long,
    private val blockSize: Int,
    private val align: Int,
) : SectorReader {
    private val numBlocks: Long = (totalBytes + blockSize - 1) / blockSize
    private var cachedIndex = -1L
    private var cachedBlock: ByteArray? = null

    override fun sector(lba: Long): ByteArray? {
        val offset = lba * 2048
        if (offset + 2048 > totalBytes) return null
        val out = ByteArray(2048)
        var done = 0
        while (done < 2048) {
            val pos = offset + done
            val blockIdx = pos / blockSize
            val block = block(blockIdx) ?: return null
            val inBlock = (pos % blockSize).toInt()
            val n = minOf(2048 - done, blockSize - inBlock)
            System.arraycopy(block, inBlock, out, done, n)
            done += n
        }
        return out
    }

    private fun block(i: Long): ByteArray? {
        if (i == cachedIndex) return cachedBlock
        if (i >= numBlocks) return null
        val idx = src.readExact(0x18 + i * 4, 8) ?: return null
        val cur = idx.u32le(0)
        val next = idx.u32le(4)
        val plain = (cur and 0x80000000L) != 0L
        val pos = (cur and 0x7FFFFFFFL) shl align
        val nextPos = (next and 0x7FFFFFFFL) shl align
        val result = if (plain) {
            src.readExact(pos, blockSize)
        } else {
            val len = (nextPos - pos).toInt()
            if (len <= 0 || len > blockSize * 2) return null
            val compressed = src.readExact(pos, len) ?: src.read(pos, len).takeIf { it.isNotEmpty() } ?: return null
            inflateRaw(compressed, blockSize)
        }
        cachedIndex = i
        cachedBlock = result
        return result
    }

    companion object {
        private val CISO = ascii("CISO")

        /** True when [head] is a PSP CSO (as opposed to a GameCube CISO with the same magic). */
        fun isPspCso(head: ByteArray): Boolean = head.size >= 8 && head.startsWithAt(0, CISO) && head.u32le(4) == 0x18L

        fun open(src: ByteSource): CsoReader? {
            val h = src.readExact(0, 0x18) ?: return null
            if (!isPspCso(h)) return null
            val total = h.u64le(8)
            val blockSize = h.u32le(0x10).toInt()
            val align = h.u8(0x15)
            if (total <= 0 || blockSize < 2048 || blockSize > 1 shl 20 || align > 16) return null
            return CsoReader(src, total, blockSize, align)
        }

        internal fun inflateRaw(data: ByteArray, expected: Int): ByteArray? {
            val inflater = Inflater(true)
            return try {
                inflater.setInput(data)
                val out = ByteArray(expected)
                var n = 0
                while (n < expected && !inflater.finished()) {
                    val r = inflater.inflate(out, n, expected - n)
                    if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += r
                }
                if (n == expected) out else null
            } catch (e: java.util.zip.DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }
    }
}
