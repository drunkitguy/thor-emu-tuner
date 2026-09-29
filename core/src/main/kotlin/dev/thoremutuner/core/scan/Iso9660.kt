package dev.thoremutuner.core.scan

/** Supplies 2048-byte ISO9660 user-data sectors. */
fun interface SectorReader {
    /** Returns the 2048 user-data bytes of sector [lba], or null past the end. */
    fun sector(lba: Long): ByteArray?
}

/** Plain 2048-byte-sector image (.iso). */
class PlainSectorReader(private val src: ByteSource) : SectorReader {
    override fun sector(lba: Long): ByteArray? = src.readExact(lba * 2048, 2048)
}

/**
 * Raw 2352-byte CD sectors (.bin from a MODE1/2352 or MODE2/2352 cue). Mode byte @15: 1 -> user
 * data @16, 2 -> @24 (form 1).
 */
class RawSectorReader(private val src: ByteSource) : SectorReader {
    override fun sector(lba: Long): ByteArray? {
        val raw = src.readExact(lba * RAW_SECTOR, RAW_SECTOR) ?: return null
        val start = if (raw.u8(15) == 2) 24 else 16
        return raw.copyOfRange(start, start + 2048)
    }

    companion object {
        const val RAW_SECTOR = 2352
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)

        /** True if [head] starts with the CD sync pattern `00 FF x10 00`. */
        fun hasSync(head: ByteArray): Boolean = head.startsWithAt(0, SYNC)
    }
}

data class IsoEntry(val name: String, val lba: Long, val size: Long, val isDirectory: Boolean)

/** Minimal ISO9660 reader: primary volume descriptor, directory walk, small file reads. */
class Iso9660 private constructor(private val reader: SectorReader, private val root: IsoEntry) {

    /** Lists a directory, reading at most [maxSectors] sectors of it. */
    fun list(dir: IsoEntry, maxSectors: Int = MAX_DIR_SECTORS): List<IsoEntry> {
        val out = mutableListOf<IsoEntry>()
        val sectors = minOf(maxSectors.toLong(), (dir.size + 2047) / 2048)
        for (i in 0 until sectors) {
            val sec = reader.sector(dir.lba + i) ?: break
            var p = 0
            while (p < 2048) {
                val len = sec.u8(p)
                if (len == 0) break // rest of this sector is padding
                if (p + 33 > 2048 || p + len > 2048) break
                val nameLen = sec.u8(p + 32)
                if (p + 33 + nameLen > 2048) break
                val rawName = String(sec, p + 33, nameLen, Charsets.ISO_8859_1)
                val isSelfOrParent = nameLen == 1 && (sec[p + 33].toInt() == 0 || sec[p + 33].toInt() == 1)
                if (!isSelfOrParent) {
                    out += IsoEntry(
                        name = rawName.substringBefore(';').trimEnd('.'),
                        lba = sec.u32le(p + 2),
                        size = sec.u32le(p + 10),
                        isDirectory = (sec.u8(p + 25) and 0x02) != 0,
                    )
                }
                p += len
            }
        }
        return out
    }

    /** Finds a path like "PSP_GAME/PARAM.SFO" (case-insensitive). */
    fun find(path: String): IsoEntry? {
        var current = root
        for (part in path.split('/', '\\').filter { it.isNotEmpty() }) {
            if (!current.isDirectory) return null
            current = list(current).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return current
    }

    /** Reads up to [maxBytes] of a file. */
    fun readFile(entry: IsoEntry, maxBytes: Int = 16 * 1024): ByteArray? {
        val total = minOf(entry.size, maxBytes.toLong()).toInt()
        val out = java.io.ByteArrayOutputStream(total)
        var lba = entry.lba
        while (out.size() < total) {
            val sec = reader.sector(lba++) ?: return null
            out.write(sec, 0, minOf(2048, total - out.size()))
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_DIR_SECTORS = 64
        private val CD001 = ascii("CD001")

        /** Opens the image if sector 16 is a primary volume descriptor ("\u0001CD001"). */
        fun open(reader: SectorReader): Iso9660? {
            val pvd = reader.sector(16) ?: return null
            if (pvd.u8(0) != 1 || !pvd.startsWithAt(1, CD001)) return null
            val rootRecord = 156
            val root = IsoEntry("", pvd.u32le(rootRecord + 2), pvd.u32le(rootRecord + 10), true)
            return Iso9660(reader, root)
        }
    }

    val rootEntry: IsoEntry get() = root
}
