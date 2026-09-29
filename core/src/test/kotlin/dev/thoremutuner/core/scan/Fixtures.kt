package dev.thoremutuner.core.scan

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Synthetic ROM/disc images built in code. No real game data is used or committed: every image
 * contains only the header fields the probes read.
 */
object Fixtures {

    // ------------------------------------------------------------------ little helpers

    fun ByteArray.putAscii(off: Int, s: String): ByteArray {
        s.toByteArray(Charsets.US_ASCII).copyInto(this, off); return this
    }

    fun ByteArray.putU32le(off: Int, v: Long): ByteArray {
        for (i in 0..3) this[off + i] = (v shr (8 * i)).toByte(); return this
    }

    fun ByteArray.putU32be(off: Int, v: Long): ByteArray {
        for (i in 0..3) this[off + i] = (v shr (8 * (3 - i))).toByte(); return this
    }

    fun ByteArray.putU64le(off: Int, v: Long): ByteArray {
        for (i in 0..7) this[off + i] = (v shr (8 * i)).toByte(); return this
    }

    fun ByteArray.putU16le(off: Int, v: Int): ByteArray {
        this[off] = v.toByte(); this[off + 1] = (v shr 8).toByte(); return this
    }

    // ------------------------------------------------------------------ GameCube / Wii

    fun gcDiscHeader(id6: String = "GMSE01", title: String = "SYNTHETIC GC", wii: Boolean = false, revision: Int = 0): ByteArray {
        val h = ByteArray(0x440)
        h.putAscii(0, id6)
        h[7] = revision.toByte()
        if (wii) h.putU32be(0x18, 0x5D1C9EA3L) else h.putU32be(0x1C, 0xC2339F3DL)
        h.putAscii(0x20, title)
        return h
    }

    fun gcIso(id6: String = "GMSE01"): ByteArray = gcDiscHeader(id6).copyOf(0x8000)
    fun wiiIso(id6: String = "RMGE01"): ByteArray = gcDiscHeader(id6, "SYNTHETIC WII", wii = true).copyOf(0x8000)

    fun wbfs(id6: String = "RSBE01", shift: Int = 9): ByteArray {
        val hd = 1 shl shift
        val out = ByteArray(hd + 0x440 + 0x1000)
        out.putAscii(0, "WBFS")
        out[8] = shift.toByte()
        gcDiscHeader(id6, "SYNTHETIC WBFS", wii = true).copyInto(out, hd)
        return out
    }

    fun rvz(id6: String = "GZLE01", wii: Boolean = false, magic: String = "RVZ"): ByteArray {
        val out = ByteArray(0x58 + 0x80 + 0x100)
        out.putAscii(0, magic); out[3] = 1
        gcDiscHeader(id6, "SYNTHETIC RVZ", wii = wii).copyOfRange(0, 0x80).copyInto(out, 0x58)
        return out
    }

    /** GameCube CISO: "CISO", block_size @4 (2 MiB), map @8, disc data at 0x8000. */
    fun gcCiso(id6: String = "GALE01"): ByteArray {
        val out = ByteArray(0x8000 + 0x440)
        out.putAscii(0, "CISO")
        out.putU32le(4, 0x200000L)
        out[8] = 1
        gcDiscHeader(id6, "SYNTHETIC CISO").copyInto(out, 0x8000)
        return out
    }

    fun gcz(): ByteArray = ByteArray(0x100).putU32le(0, 0xB10BC001L)

    // ------------------------------------------------------------------ ISO9660

    sealed class Node(val name: String)
    class FileNode(name: String, val data: ByteArray) : Node(name)
    class DirNode(name: String, val children: List<Node>) : Node(name)

    fun file(name: String, text: String) = FileNode(name, text.toByteArray(Charsets.ISO_8859_1))
    fun file(name: String, data: ByteArray) = FileNode(name, data)
    fun dir(name: String, vararg children: Node) = DirNode(name, children.toList())

    /** Builds a 2048-byte-sector ISO9660 image (PVD at sector 16, one sector per directory). */
    fun iso(vararg rootChildren: Node): ByteArray {
        val root = DirNode("", rootChildren.toList())
        val dirLba = LinkedHashMap<DirNode, Int>()
        val fileLba = LinkedHashMap<FileNode, Int>()
        var next = 18
        fun assignDirs(d: DirNode) { dirLba[d] = next++; d.children.filterIsInstance<DirNode>().forEach { assignDirs(it) } }
        assignDirs(root)
        fun assignFiles(d: DirNode) {
            d.children.forEach { c ->
                when (c) {
                    is FileNode -> { fileLba[c] = next; next += maxOf(1, (c.data.size + 2047) / 2048) }
                    is DirNode -> assignFiles(c)
                }
            }
        }
        assignFiles(root)
        val img = ByteArray(next * 2048)
        // PVD
        val pvd = 16 * 2048
        img[pvd] = 1; img.putAscii(pvd + 1, "CD001"); img[pvd + 6] = 1
        record(img, pvd + 156, byteArrayOf(0), dirLba.getValue(root), 2048, true)
        // terminator
        img[17 * 2048] = 0xFF.toByte(); img.putAscii(17 * 2048 + 1, "CD001")
        for ((d, lba) in dirLba) {
            var p = lba * 2048
            p += record(img, p, byteArrayOf(0), lba, 2048, true)
            p += record(img, p, byteArrayOf(1), lba, 2048, true)
            for (c in d.children) {
                p += when (c) {
                    is DirNode -> record(img, p, c.name.toByteArray(), dirLba.getValue(c), 2048, true)
                    is FileNode -> record(img, p, "${c.name};1".toByteArray(), fileLba.getValue(c), c.data.size, false)
                }
            }
        }
        for ((f, lba) in fileLba) f.data.copyInto(img, lba * 2048)
        return img
    }

    private fun record(img: ByteArray, at: Int, name: ByteArray, lba: Int, size: Int, dir: Boolean): Int {
        val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
        img[at] = len.toByte()
        img.putU32le(at + 2, lba.toLong()); img.putU32be(at + 6, lba.toLong())
        img.putU32le(at + 10, size.toLong()); img.putU32be(at + 14, size.toLong())
        img[at + 25] = if (dir) 2 else 0
        img[at + 32] = name.size.toByte()
        name.copyInto(img, at + 33)
        return len
    }

    /** Wraps 2048-byte sectors into raw 2352-byte MODE2 form 1 sectors (or MODE1). */
    fun raw2352(iso: ByteArray, mode: Int = 2): ByteArray {
        val sectors = iso.size / 2048
        val out = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val o = s * 2352
            out[o] = 0; for (i in 1..10) out[o + i] = 0xFF.toByte(); out[o + 11] = 0
            out[o + 15] = mode.toByte()
            val dataAt = if (mode == 2) 24 else 16
            iso.copyInto(out, o + dataAt, s * 2048, s * 2048 + 2048)
        }
        return out
    }

    fun ps2Iso(boot: String = "cdrom0:\\SLUS_203.12;1"): ByteArray =
        iso(file("SYSTEM.CNF", "BOOT2 = $boot\r\nVER = 1.00\r\nVMODE = NTSC\r\n"), file("SLUS_203.12", ByteArray(100)))

    fun ps1Iso(boot: String = "cdrom:\\SCUS_941.63;1"): ByteArray =
        iso(file("SYSTEM.CNF", "BOOT = $boot\r\nTCB = 4\r\nEVENT = 10\r\n"))

    fun pspIsoUmd(discId: String = "ULUS-10041"): ByteArray =
        iso(file("UMD_DATA.BIN", "$discId|0001|0001|G"), dir("PSP_GAME", file("ICON0.PNG", ByteArray(10))))

    fun pspIsoSfo(discId: String = "ULES00151"): ByteArray =
        iso(dir("PSP_GAME", file("PARAM.SFO", sfo(mapOf("DISC_ID" to discId, "TITLE" to "Synthetic PSP", "CATEGORY" to "UG")))))

    // ------------------------------------------------------------------ PSP containers

    /** PARAM.SFO with UTF-8 string entries (format 0x0204). */
    fun sfo(entries: Map<String, String>): ByteArray {
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        for ((k, v) in entries) {
            val keyOff = keys.size()
            keys.write(k.toByteArray()); keys.write(0)
            val value = v.toByteArray() + 0
            val max = (value.size + 3) / 4 * 4
            val dataOff = data.size()
            data.write(value); repeat(max - value.size) { data.write(0) }
            val e = ByteArray(16)
            e.putU16le(0, keyOff); e.putU16le(2, 0x0204); e.putU32le(4, value.size.toLong())
            e.putU32le(8, max.toLong()); e.putU32le(12, dataOff.toLong())
            index.write(e)
        }
        while (keys.size() % 4 != 0) keys.write(0)
        val keyTable = 20 + index.size()
        val dataTable = keyTable + keys.size()
        val h = ByteArray(20)
        h[0] = 0; h.putAscii(1, "PSF"); h.putU32le(4, 0x0101); h.putU32le(8, keyTable.toLong())
        h.putU32le(12, dataTable.toLong()); h.putU32le(16, entries.size.toLong())
        return h + index.toByteArray() + keys.toByteArray() + data.toByteArray()
    }

    fun pbp(discId: String = "NPUH10117", title: String = "Synthetic PBP", category: String = "PG"): ByteArray {
        val s = sfo(mapOf("DISC_ID" to discId, "TITLE" to title, "CATEGORY" to category))
        val h = ByteArray(0x28)
        h[0] = 0; h.putAscii(1, "PBP"); h.putU32le(4, 0x00010000)
        val off0 = 0x28L
        val off1 = off0 + s.size
        h.putU32le(8, off0)
        for (i in 1..7) h.putU32le(8 + i * 4, off1)
        return h + s + ByteArray(64)
    }

    /** PSP CSO v1 (header 0x18, block 2048, align 0). Zero blocks are deflated, others stored plain. */
    fun cso(iso: ByteArray, forcePlainFirstData: Boolean = true): ByteArray {
        val blockSize = 2048
        val n = (iso.size + blockSize - 1) / blockSize
        val blocks = ArrayList<Pair<ByteArray, Boolean>>()
        for (i in 0 until n) {
            val block = iso.copyOfRange(i * blockSize, minOf(iso.size, (i + 1) * blockSize)).copyOf(blockSize)
            val deflater = Deflater(9, true)
            deflater.setInput(block); deflater.finish()
            val buf = ByteArray(blockSize * 2)
            val len = deflater.deflate(buf); deflater.end()
            val plain = len >= blockSize || (forcePlainFirstData && i == 16)
            blocks += if (plain) block to true else buf.copyOf(len) to false
        }
        val header = ByteArray(0x18)
        header.putAscii(0, "CISO"); header.putU32le(4, 0x18); header.putU64le(8, iso.size.toLong())
        header.putU32le(0x10, blockSize.toLong()); header[0x14] = 1; header[0x15] = 0
        val indexSize = (n + 1) * 4
        val index = ByteArray(indexSize)
        var pos = (0x18 + indexSize).toLong()
        val body = ByteArrayOutputStream()
        for ((i, b) in blocks.withIndex()) {
            index.putU32le(i * 4, pos or (if (b.second) 0x80000000L else 0L))
            body.write(b.first); pos += b.first.size
        }
        index.putU32le(n * 4, pos)
        return header + index + body.toByteArray()
    }

    // ------------------------------------------------------------------ cartridges

    fun cci(titleId: Long = 0x0004000000055D00L, product: String = "CTR-P-AAAE"): ByteArray {
        val out = ByteArray(0x4000 + 0x200)
        out.putAscii(0x100, "NCSD"); out.putU64le(0x108, titleId)
        out.putU32le(0x120, 0x20) // partition 0 at 0x20 * 0x200 = 0x4000
        out.putAscii(0x4000 + 0x100, "NCCH"); out.putU64le(0x4000 + 0x118, titleId)
        out.putAscii(0x4000 + 0x150, product)
        return out
    }

    fun cxi(programId: Long = 0x000400000F700000L, product: String = "CTR-P-CTAP"): ByteArray {
        val out = ByteArray(0x200)
        out.putAscii(0x100, "NCCH"); out.putU64le(0x118, programId); out.putAscii(0x150, product)
        return out
    }

    fun nds(title: String = "SYNTH DS", code: String = "ADAE", maker: String = "01"): ByteArray =
        ByteArray(0x200).putAscii(0, title).putAscii(0x0C, code).putAscii(0x10, maker)

    fun gba(title: String = "SYNTH GBA", code: String = "BPEE"): ByteArray =
        ByteArray(0x200).putAscii(0xA0, title).putAscii(0xAC, code)

    fun n64z64(title: String = "SYNTH N64", code: String = "NSME"): ByteArray =
        ByteArray(0x1000).putU32be(0, 0x80371240L).putAscii(0x20, title).putAscii(0x3B, code)

    fun n64v64(): ByteArray {
        val z = n64z64(); val out = z.copyOf()
        for (i in 0 until z.size step 2) { out[i] = z[i + 1]; out[i + 1] = z[i] }
        return out
    }

    fun n64n64(): ByteArray {
        val z = n64z64(); val out = z.copyOf()
        for (i in 0 until z.size step 4) { out[i] = z[i + 3]; out[i + 1] = z[i + 2]; out[i + 2] = z[i + 1]; out[i + 3] = z[i] }
        return out
    }

    fun genesis(): ByteArray = ByteArray(0x400).putAscii(0x100, "SEGA MEGA DRIVE ")
}

/** Counts every byte read (criterion 13). */
class CountingByteSource(private val inner: ByteSource) : ByteSource {
    var total = 0L
        private set
    override val size: Long get() = inner.size
    override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length).also { total += it.size }
}

/** A huge virtual image (e.g. 4.7 GB) backed by a small header; everything else reads as zeros. */
class SparseByteSource(private val head: ByteArray, override val size: Long) : ByteSource {
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size) return ByteArray(0)
        val n = minOf(length.toLong(), size - offset).toInt()
        val out = ByteArray(n)
        if (offset < head.size) {
            val m = minOf(n, head.size - offset.toInt())
            head.copyInto(out, 0, offset.toInt(), offset.toInt() + m)
        }
        return out
    }
}

/** In-memory SAF-like tree. Document ids look like "primary:ROMs/psp/Game.iso". */
class FakeTree(private val rootId: String = "primary:ROMs") : TreeAccess {
    private val files = LinkedHashMap<String, ByteArray>()
    private val dirs = LinkedHashSet<String>().apply { add(rootId) }
    val opened = mutableListOf<String>()

    fun add(relPath: String, data: ByteArray): FakeTree {
        val id = "$rootId/$relPath"
        files[id] = data
        var parent = id.substringBeforeLast('/')
        while (parent.length > rootId.length) { dirs += parent; parent = parent.substringBeforeLast('/') }
        return this
    }

    fun add(relPath: String, text: String) = add(relPath, text.toByteArray())
    fun mkdir(relPath: String): FakeTree { dirs += "$rootId/$relPath"; return this }

    fun root(treeUri: String = "content://com.android.externalstorage.documents/tree/primary%3AROMs") =
        RomRoot(treeUri, rootId, rootId.substringAfterLast('/').substringAfter(':'))

    override fun children(dirDocumentId: String): List<DocEntry> {
        val prefix = "$dirDocumentId/"
        val out = mutableListOf<DocEntry>()
        dirs.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .forEach { out += DocEntry(it, it.substringAfterLast('/'), true, 0, 0) }
        files.filter { it.key.startsWith(prefix) && !it.key.removePrefix(prefix).contains('/') }
            .forEach { (id, data) -> out += DocEntry(id, id.substringAfterLast('/'), false, data.size.toLong(), 1000) }
        return out
    }

    override fun open(documentId: String): ByteSource {
        opened += documentId
        return ArrayByteSource(files[documentId] ?: throw java.io.FileNotFoundException("missing $documentId"))
    }
}
