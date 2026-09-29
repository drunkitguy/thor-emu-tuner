package dev.thoremutuner.app.saf

import android.os.ParcelFileDescriptor
import dev.thoremutuner.core.scan.ByteSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random access over a SAF document: `openFileDescriptor(uri, "r")` -> FileInputStream channel with
 * positional reads (PLAN section 6.1). One channel per probe; closed by the scanner.
 */
class SafByteSource(private val pfd: ParcelFileDescriptor) : ByteSource {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = stream.channel

    override val size: Long = pfd.statSize.takeIf { it >= 0 } ?: runCatching { channel.size() }.getOrDefault(0L)

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0 || offset >= size) return ByteArray(0)
        val want = minOf(length.toLong(), size - offset).toInt()
        val buf = ByteBuffer.allocate(want)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return if (buf.position() == want) buf.array() else buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }
}
