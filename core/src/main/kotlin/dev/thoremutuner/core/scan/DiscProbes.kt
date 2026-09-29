package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId

/** GameCube/Wii containers and ISO9660 discs (PS1, PS2, PSP). Offsets per PLAN section 6.3. */
object DiscProbes {
    private const val WII_MAGIC = 0x5D1C9EA3L
    private const val GC_MAGIC = 0xC2339F3DL
    private const val GCZ_MAGIC = 0xB10BC001L
    private val WBFS = ascii("WBFS")
    private val WIA = byteArrayOf(0x57, 0x49, 0x41, 0x01)
    private val RVZ = byteArrayOf(0x52, 0x56, 0x5A, 0x01)
    private val CISO = ascii("CISO")
    private val ID6 = Regex("^[A-Z0-9]{6}$")

    // ---------------------------------------------------------------- GameCube / Wii

    /** Tries plain ISO/GCM, WBFS, WIA/RVZ and GC CISO. GCZ is recognized but not decoded (v1). */
    fun probeGcWii(src: ByteSource): ProbeResult? {
        val head = src.read(0, 0x60)
        if (head.size < 0x20) return null
        return when {
            head.startsWithAt(0, WBFS) -> {
                val shift = head.u8(8)
                if (shift !in 9..30) return null
                val hd = 1L shl shift
                discHeader(src, hd, DetectionMethod.CONTAINER_METADATA, forceWii = true)
            }
            head.startsWithAt(0, WIA) || head.startsWithAt(0, RVZ) ->
                discHeader(src, 0x58, DetectionMethod.CONTAINER_METADATA)
            head.startsWithAt(0, CISO) && head.u32le(4) != 0x18L -> {
                // GameCube CISO: block_size @4, map @8; data of block 0 at 0x8000 when mapped.
                if (head.u8(8) == 1) discHeader(src, 0x8000, DetectionMethod.CONTAINER_METADATA) else null
            }
            head.u32le(0) == GCZ_MAGIC -> null // GCZ decoding is planned after v0.1 (filename fallback applies)
            else -> discHeader(src, 0, DetectionMethod.HEADER)
        }
    }

    /** True for a GCZ container (used to classify the system from the extension/folder only). */
    fun isGcz(head: ByteArray): Boolean = head.size >= 4 && head.u32le(0) == GCZ_MAGIC

    private fun discHeader(src: ByteSource, at: Long, method: DetectionMethod, forceWii: Boolean = false): ProbeResult? {
        val h = src.readExact(at, 0x60) ?: return null
        val isWii = h.u32be(0x18) == WII_MAGIC
        val isGc = h.u32be(0x1C) == GC_MAGIC
        if (!isWii && !isGc && !forceWii) return null
        val id = String(h, 0, 6, Charsets.US_ASCII)
        if (!ID6.matches(id)) return null
        val title = h.cString(0x20, 64)
        val extra = buildMap {
            put("discRevision", h.u8(7).toString())
            if (title.isNotEmpty()) put("headerTitle", title)
        }
        return ProbeResult(
            system = if (isWii || forceWii) SystemId.WII else SystemId.GC,
            id = DetectedId(id, IdKind.GC_WII_ID6, method, extra),
            headerTitle = title.ifEmpty { null },
        )
    }

    // ---------------------------------------------------------------- ISO9660 discs

    /** Opens plain (2048) or raw (2352) ISO9660 images, or a PSP CSO. */
    fun openIso(src: ByteSource): Iso9660? {
        val head = src.read(0, 16)
        if (CsoReader.isPspCso(head)) return CsoReader.open(src)?.let { Iso9660.open(it) }
        if (RawSectorReader.hasSync(head)) return Iso9660.open(RawSectorReader(src))
        return Iso9660.open(PlainSectorReader(src))
    }

    /** PS2 (BOOT2), PS1 (BOOT) or PSP (UMD_DATA.BIN / PARAM.SFO) from an ISO9660 filesystem. */
    fun probeIsoFilesystem(iso: Iso9660, method: DetectionMethod = DetectionMethod.HEADER): ProbeResult? {
        val root = iso.list(iso.rootEntry)
        root.firstOrNull { !it.isDirectory && it.name.equals("SYSTEM.CNF", ignoreCase = true) }?.let { cnf ->
            val text = iso.readFile(cnf, 4096)?.toString(Charsets.ISO_8859_1) ?: return@let
            parseSystemCnf(text)?.let { (system, serial) ->
                val kind = if (system == SystemId.PS2) IdKind.PS2_SERIAL else IdKind.PSX_SERIAL
                return ProbeResult(system, DetectedId(serial, kind, method))
            }
        }
        root.firstOrNull { !it.isDirectory && it.name.equals("UMD_DATA.BIN", ignoreCase = true) }?.let { umd ->
            val text = iso.readFile(umd, 256)?.toString(Charsets.ISO_8859_1) ?: return@let
            val discId = text.substringBefore('|').replace("-", "").trim().uppercase()
            if (PSP_ID.matches(discId)) return ProbeResult(SystemId.PSP, DetectedId(discId, IdKind.PSP_DISC_ID, method))
        }
        if (root.any { it.isDirectory && it.name.equals("PSP_GAME", ignoreCase = true) }) {
            val sfoEntry = iso.find("PSP_GAME/PARAM.SFO")
            val sfo = sfoEntry?.let { iso.readFile(it, 64 * 1024) }?.let { SfoParser.parse(it) }
            val discId = sfo?.get("DISC_ID")?.replace("-", "")?.uppercase()
            if (discId != null && PSP_ID.matches(discId)) {
                return ProbeResult(
                    SystemId.PSP,
                    DetectedId(discId, IdKind.PSP_DISC_ID, method, sfo["TITLE"]?.let { mapOf("headerTitle" to it) } ?: emptyMap()),
                    sfo["TITLE"],
                )
            }
            return ProbeResult(SystemId.PSP, null)
        }
        return null
    }

    private val PSP_ID = Regex("^[A-Z]{4}\\d{5}$")
    private val SERIAL = Regex("^[A-Z]{4}-\\d{5}$")

    /**
     * `BOOT2 = cdrom0:\SLUS_203.12;1` -> (PS2, "SLUS-20312"); `BOOT = cdrom:\SCUS_941.63;1` -> (PSX, "SCUS-94163").
     */
    fun parseSystemCnf(text: String): Pair<SystemId, String>? {
        val lines = text.lines().map { it.trim() }
        fun value(key: String) = lines.firstOrNull { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
            ?.substringAfter('=')?.trim()
        value("BOOT2")?.let { v -> serialFromBootPath(v)?.let { return SystemId.PS2 to it } }
        value("BOOT")?.let { v -> serialFromBootPath(v)?.let { return SystemId.PSX to it } }
        return null
    }

    fun serialFromBootPath(path: String): String? {
        val file = path.substringAfterLast('\\').substringAfterLast(':').substringAfterLast('/')
            .substringBefore(';').trim().uppercase()
        if (file.length < 5) return null
        val prefix = file.take(4)
        val digits = file.drop(4).filter { it != '_' && it != '.' && it != '-' }
        val serial = "$prefix-$digits"
        return serial.takeIf { SERIAL.matches(it) }
    }

    /** PBP eboot: DISC_ID from the embedded PARAM.SFO. PS1 classics (CATEGORY "ME" or PS1-style ids) map to PSX. */
    fun probePbp(src: ByteSource): ProbeResult? {
        val sfo = SfoParser.readPbpSfo(src) ?: return null
        val discId = sfo["DISC_ID"]?.replace("-", "")?.uppercase() ?: return null
        val title = sfo["TITLE"]
        val extra = title?.let { mapOf("headerTitle" to it) } ?: emptyMap()
        val isPs1 = sfo["CATEGORY"] == "ME" || Regex("^(S[CL][UEPAKC][SMD]|SL[EPU]S)\\d{5}$").matches(discId)
        return if (isPs1) {
            val serial = discId.take(4) + "-" + discId.drop(4)
            ProbeResult(SystemId.PSX, DetectedId(serial, IdKind.PSX_SERIAL, DetectionMethod.CONTAINER_METADATA, extra), title)
        } else {
            if (!PSP_ID.matches(discId)) return null
            ProbeResult(SystemId.PSP, DetectedId(discId, IdKind.PSP_DISC_ID, DetectionMethod.CONTAINER_METADATA, extra), title)
        }
    }

    /** Genesis/Mega Drive cartridges carry "SEGA" at 0x100. */
    fun isGenesisCart(src: ByteSource): Boolean {
        val h = src.readExact(0x100, 4) ?: return false
        return h.startsWithAt(0, ascii("SEGA"))
    }
}
