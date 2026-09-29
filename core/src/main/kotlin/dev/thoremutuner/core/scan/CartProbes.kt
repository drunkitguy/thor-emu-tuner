package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId

/** Cartridge-style headers: 3DS (NCSD/NCCH), NDS, GBA, N64. Offsets per PLAN section 6.3. */
object CartProbes {
    private val NCSD = ascii("NCSD")
    private val NCCH = ascii("NCCH")
    private val CODE4 = Regex("^[A-Z0-9]{4}$")

    /** .3ds/.cci (NCSD) or .cxi (NCCH): title/program id as %016X plus the product code. */
    fun probe3ds(src: ByteSource): ProbeResult? {
        val h = src.readExact(0, 0x200) ?: return null
        if (h.startsWithAt(0x100, NCSD)) {
            val titleId = "%016X".format(h.u64le(0x108))
            val partition0 = h.u32le(0x120) * 0x200
            val ncch = if (partition0 > 0) src.readExact(partition0, 0x200) else null
            val extra = mutableMapOf<String, String>()
            if (ncch != null && ncch.startsWithAt(0x100, NCCH)) {
                extra["programId"] = "%016X".format(ncch.u64le(0x118))
                val product = ncch.cString(0x150, 16)
                if (product.isNotEmpty()) extra["productCode"] = product
            }
            return ProbeResult(SystemId.N3DS, DetectedId(titleId, IdKind.N3DS_TITLE_ID, DetectionMethod.HEADER, extra))
        }
        if (h.startsWithAt(0x100, NCCH)) {
            val programId = "%016X".format(h.u64le(0x118))
            val product = h.cString(0x150, 16)
            val extra = if (product.isNotEmpty()) mapOf("productCode" to product) else emptyMap()
            return ProbeResult(SystemId.N3DS, DetectedId(programId, IdKind.N3DS_TITLE_ID, DetectionMethod.HEADER, extra))
        }
        return null
    }

    /** NDS: title @0x00 (12), game code @0x0C (4), maker @0x10 (2). */
    fun probeNds(src: ByteSource): ProbeResult? {
        val h = src.readExact(0, 0x12) ?: return null
        val code = String(h, 0x0C, 4, Charsets.US_ASCII)
        if (!CODE4.matches(code)) return null
        val title = h.cString(0, 12)
        val extra = buildMap {
            put("maker", String(h, 0x10, 2, Charsets.US_ASCII))
            if (title.isNotEmpty()) put("headerTitle", title)
        }
        return ProbeResult(SystemId.NDS, DetectedId(code, IdKind.NDS_GAME_CODE, DetectionMethod.HEADER, extra), title.ifEmpty { null })
    }

    /** GBA: title @0xA0 (12), game code @0xAC (4). */
    fun probeGba(src: ByteSource): ProbeResult? {
        val h = src.readExact(0, 0xC0) ?: return null
        val code = String(h, 0xAC, 4, Charsets.US_ASCII)
        if (!CODE4.matches(code)) return null
        val title = h.cString(0xA0, 12)
        val extra = if (title.isNotEmpty()) mapOf("headerTitle" to title) else emptyMap()
        return ProbeResult(SystemId.GBA, DetectedId(code, IdKind.GBA_GAME_CODE, DetectionMethod.HEADER, extra), title.ifEmpty { null })
    }

    enum class N64Order { Z64, V64, N64 }

    fun n64Order(head: ByteArray): N64Order? {
        if (head.size < 4) return null
        val sig = head.u32be(0)
        return when (sig) {
            0x80371240L -> N64Order.Z64
            0x37804012L -> N64Order.V64
            0x40123780L -> N64Order.N64
            else -> null
        }
    }

    /** Converts the first bytes of an N64 image to big-endian (z64) order. */
    fun normalizeN64(head: ByteArray, order: N64Order): ByteArray {
        val out = head.copyOf()
        when (order) {
            N64Order.Z64 -> Unit
            N64Order.V64 -> for (i in 0 until out.size - 1 step 2) {
                out[i] = head[i + 1]; out[i + 1] = head[i]
            }
            N64Order.N64 -> for (i in 0 until out.size - 3 step 4) {
                out[i] = head[i + 3]; out[i + 1] = head[i + 2]; out[i + 2] = head[i + 1]; out[i + 3] = head[i]
            }
        }
        return out
    }

    /** N64: name @0x20 (20 B), game code = bytes 0x3B..0x3E after byte-order normalization. */
    fun probeN64(src: ByteSource): ProbeResult? {
        val raw = src.readExact(0, 0x40) ?: return null
        val order = n64Order(raw) ?: return null
        val h = normalizeN64(raw, order)
        val code = String(h, 0x3B, 4, Charsets.US_ASCII)
        val title = h.cString(0x20, 20)
        val extra = buildMap {
            put("byteOrder", order.name.lowercase())
            if (title.isNotEmpty()) put("headerTitle", title)
        }
        if (!CODE4.matches(code)) return ProbeResult(SystemId.N64, null, title.ifEmpty { null })
        return ProbeResult(SystemId.N64, DetectedId(code, IdKind.N64_GAME_CODE, DetectionMethod.HEADER, extra), title.ifEmpty { null })
    }
}
