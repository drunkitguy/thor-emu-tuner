package dev.thoremutuner.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Game systems. [id] is the lowercase ES-DE id used in JSON; [folderNames] are the folder names
 * (ES-DE names first) that identify a system during a scan.
 */
@Serializable(with = SystemIdSerializer::class)
enum class SystemId(val id: String, val displayName: String, val folderNames: List<String>) {
    GC("gc", "GameCube", listOf("gc", "gamecube")),
    WII("wii", "Wii", listOf("wii")),
    PS2("ps2", "PlayStation 2", listOf("ps2")),
    PSX("psx", "PlayStation", listOf("psx", "ps1", "playstation")),
    PSP("psp", "PSP", listOf("psp")),
    N3DS("n3ds", "Nintendo 3DS", listOf("n3ds", "3ds")),
    NDS("nds", "Nintendo DS", listOf("nds")),
    SWITCH("switch", "Switch", listOf("switch")),
    PSVITA("psvita", "PS Vita", listOf("psvita", "vita")),
    NES("nes", "NES", listOf("nes", "famicom")),
    SNES("snes", "SNES", listOf("snes", "sfc")),
    GB("gb", "Game Boy", listOf("gb")),
    GBC("gbc", "Game Boy Color", listOf("gbc")),
    GBA("gba", "Game Boy Advance", listOf("gba")),
    N64("n64", "Nintendo 64", listOf("n64")),
    GENESIS("genesis", "Genesis / Mega Drive", listOf("genesis", "megadrive")),
    MASTERSYSTEM("mastersystem", "Master System", listOf("mastersystem")),
    GAMEGEAR("gamegear", "Game Gear", listOf("gamegear")),
    DREAMCAST("dreamcast", "Dreamcast", listOf("dreamcast")),
    WINDOWS("windows", "Windows (Winlator)", listOf("windows", "pc")),
    UNKNOWN("unknown", "Unrecognized", emptyList());

    /** Ids that may appear in preset JSON for this system ("megadrive" is an alias of genesis). */
    val jsonIds: List<String> get() = if (this == GENESIS) listOf("genesis", "megadrive") else listOf(id)

    companion object {
        /** Maps a JSON id (including aliases) to a system; unknown ids (e.g. "naomi") map to null. */
        fun fromId(id: String): SystemId? {
            val lower = id.lowercase()
            if (lower == "megadrive") return GENESIS
            return entries.firstOrNull { it.id == lower && it != UNKNOWN }
        }

        /** Maps a folder name (case-insensitive) to a system, or null. */
        fun fromFolderName(name: String): SystemId? {
            val lower = name.lowercase()
            return entries.firstOrNull { lower in it.folderNames }
        }
    }
}

object SystemIdSerializer : KSerializer<SystemId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SystemId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SystemId) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): SystemId = SystemId.fromId(decoder.decodeString()) ?: SystemId.UNKNOWN
}

@Serializable
enum class IdKind(val label: String) {
    GC_WII_ID6("Game ID"),
    PS2_SERIAL("Serial"),
    PSX_SERIAL("Serial"),
    PSP_DISC_ID("Disc ID"),
    N3DS_TITLE_ID("Title ID"),
    SWITCH_TITLE_ID("Title ID"),
    NDS_GAME_CODE("Game code"),
    GBA_GAME_CODE("Game code"),
    N64_GAME_CODE("Game code"),
    VITA_TITLE_ID("Title ID"),
    NONE("ID"),
}

@Serializable
enum class DetectionMethod { HEADER, CONTAINER_METADATA, FILENAME, MANUAL }

/** A game identifier plus how it was found. [extra] holds e.g. "productCode", "discRevision", "headerTitle". */
@Serializable
data class DetectedId(
    val value: String,
    val kind: IdKind,
    val method: DetectionMethod,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * One scanned game. [treeUri] and [documentId] locate the file through SAF; they are never exported
 * (privacy, PLAN section 10). [key] is a stable, non-reversible hash of both.
 */
@Serializable
data class Game(
    val key: String,
    val title: String,
    val fileName: String,
    val system: SystemId,
    val treeUri: String,
    val documentId: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val id: DetectedId? = null,
    val relatedDocumentIds: List<String> = emptyList(),
    /** Probe error message (never contains paths). The game is still listed. */
    val scanError: String? = null,
)

/** The regex each id kind must match before a (manual) id is saved. */
object IdValidation {
    private val patterns: Map<IdKind, Regex> = mapOf(
        IdKind.GC_WII_ID6 to Regex("^[A-Z0-9]{6}$"),
        IdKind.PS2_SERIAL to Regex("^[A-Z]{4}-\\d{5}$"),
        IdKind.PSX_SERIAL to Regex("^[A-Z]{4}-\\d{5}$"),
        IdKind.PSP_DISC_ID to Regex("^[A-Z]{4}\\d{5}$"),
        IdKind.N3DS_TITLE_ID to Regex("^[0-9A-F]{16}$"),
        IdKind.SWITCH_TITLE_ID to Regex("^01[0-9A-F]{14}$"),
        IdKind.NDS_GAME_CODE to Regex("^[A-Z0-9]{4}$"),
        IdKind.GBA_GAME_CODE to Regex("^[A-Z0-9]{4}$"),
        IdKind.N64_GAME_CODE to Regex("^[A-Z0-9]{4}$"),
        IdKind.VITA_TITLE_ID to Regex("^[A-Z]{4}\\d{5}$"),
    )

    /** The id kind a system uses, or [IdKind.NONE]. */
    fun kindFor(system: SystemId): IdKind = when (system) {
        SystemId.GC, SystemId.WII -> IdKind.GC_WII_ID6
        SystemId.PS2 -> IdKind.PS2_SERIAL
        SystemId.PSX -> IdKind.PSX_SERIAL
        SystemId.PSP -> IdKind.PSP_DISC_ID
        SystemId.N3DS -> IdKind.N3DS_TITLE_ID
        SystemId.SWITCH -> IdKind.SWITCH_TITLE_ID
        SystemId.NDS -> IdKind.NDS_GAME_CODE
        SystemId.GBA -> IdKind.GBA_GAME_CODE
        SystemId.N64 -> IdKind.N64_GAME_CODE
        SystemId.PSVITA -> IdKind.VITA_TITLE_ID
        else -> IdKind.NONE
    }

    /** Normalizes user input (trim, uppercase) and validates it. Returns null when invalid. */
    fun normalize(kind: IdKind, input: String): String? {
        val v = input.trim().uppercase()
        if (kind == IdKind.NONE) return v.takeIf { it.isNotEmpty() && it.length <= 64 }
        return v.takeIf { patterns.getValue(kind).matches(it) }
    }

    fun hint(kind: IdKind): String = when (kind) {
        IdKind.GC_WII_ID6 -> "6 letters/digits, e.g. GMSE01"
        IdKind.PS2_SERIAL, IdKind.PSX_SERIAL -> "4 letters, dash, 5 digits, e.g. SLUS-20312"
        IdKind.PSP_DISC_ID -> "4 letters + 5 digits, e.g. ULUS10041"
        IdKind.N3DS_TITLE_ID -> "16 hex digits, e.g. 0004000000055D00"
        IdKind.SWITCH_TITLE_ID -> "16 hex digits starting with 01"
        IdKind.NDS_GAME_CODE, IdKind.GBA_GAME_CODE, IdKind.N64_GAME_CODE -> "4 letters/digits"
        IdKind.VITA_TITLE_ID -> "e.g. PCSE00001"
        IdKind.NONE -> "free text"
    }
}
