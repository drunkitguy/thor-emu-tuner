package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.IdValidation
import dev.thoremutuner.core.model.SystemId
import java.security.MessageDigest

/** A granted ROM folder (SAF tree). */
data class RomRoot(val treeUri: String, val rootDocumentId: String, val displayName: String)

/** One child document as listed by DocumentsContract. */
data class DocEntry(
    val documentId: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** Access to one granted tree. The app implements this with DocumentsContract queries. */
interface TreeAccess {
    fun children(dirDocumentId: String): List<DocEntry>
    fun open(documentId: String): ByteSource
}

/** A file that will become a library entry, with any grouped member files already resolved. */
data class Candidate(
    val root: RomRoot,
    val entry: DocEntry,
    val folderSystem: SystemId?,
    /** Members of grouping files (m3u/cue/gdi), keyed by the grouping file's document id. */
    val groupMembers: Map<String, List<DocEntry>>,
) {
    val ext: String get() = SystemClassifier.ext(entry.displayName)
    val related: List<DocEntry> get() = groupMembers[entry.documentId].orEmpty()
}

data class ScanResult(val games: List<Game>, val errors: Int) {
    val countsBySystem: Map<SystemId, Int> get() = games.groupingBy { it.system }.eachCount()
}

/**
 * Library scanner (PLAN section 6). Phase 1 [collect] walks a tree (depth 4, skipping hidden files
 * and media folders) and resolves multi-file groups; phase 2 [probe] identifies one candidate. The
 * app runs phase 2 with bounded parallelism; [scanAll] is the sequential form.
 */
class Scanner(private val maxDepth: Int = 4) {

    fun collect(root: RomRoot, access: TreeAccess, isCancelled: () -> Boolean = { false }): List<Candidate> {
        data class Found(val entry: DocEntry, val parentId: String, val system: SystemId?)

        val listings = HashMap<String, List<DocEntry>>()
        fun list(dirId: String): List<DocEntry> = listings.getOrPut(dirId) {
            try { access.children(dirId) } catch (e: Exception) { emptyList() }
        }

        val files = mutableListOf<Found>()
        fun walk(dirId: String, depth: Int, system: SystemId?) {
            if (isCancelled()) return
            for (child in list(dirId)) {
                if (child.displayName.startsWith(".")) continue
                if (child.isDirectory) {
                    if (child.displayName.lowercase() in SystemClassifier.SKIPPED_FOLDERS) continue
                    if (depth < maxDepth) {
                        walk(child.documentId, depth + 1, SystemId.fromFolderName(child.displayName) ?: system)
                    }
                } else {
                    files += Found(child, dirId, system)
                }
            }
        }
        walk(root.rootDocumentId, 1, SystemId.fromFolderName(root.displayName))

        fun resolve(parentId: String, relative: String): DocEntry? {
            var dir = parentId
            val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            for ((i, part) in parts.withIndex()) {
                val match = list(dir).firstOrNull { it.displayName.equals(part, ignoreCase = true) } ?: return null
                if (i == parts.lastIndex) return match.takeIf { !it.isDirectory }
                if (!match.isDirectory) return null
                dir = match.documentId
            }
            return null
        }

        fun readText(entry: DocEntry): String? = try {
            access.open(entry.documentId).use { src ->
                src.read(0, minOf(src.size, MAX_TEXT.toLong()).toInt()).toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }

        val hidden = HashSet<String>()
        val groupMembers = HashMap<String, List<DocEntry>>()
        // m3u first (it may hide cue sheets), then cue/gdi (they hide their tracks).
        for (ext in listOf("m3u", "cue", "gdi")) {
            for (f in files.filter { SystemClassifier.ext(it.entry.displayName) == ext }) {
                if (isCancelled()) break
                val text = readText(f.entry) ?: continue
                val names = when (ext) {
                    "m3u" -> PlaylistParser.m3u(text)
                    "cue" -> CueParser.parse(text).map { it.name }
                    else -> PlaylistParser.gdi(text)
                }
                val members = names.mapNotNull { resolve(f.parentId, it) }
                groupMembers[f.entry.documentId] = members
                hidden += members.map { it.documentId }
            }
        }

        return files
            .filter { it.entry.documentId !in hidden }
            .filter { SystemClassifier.accepts(it.entry.displayName, it.system) }
            .map { f ->
                // Keep member lists of this entry and, for m3u, of its members (cue -> bin chains).
                val own = groupMembers[f.entry.documentId].orEmpty()
                val relevant = buildMap {
                    groupMembers[f.entry.documentId]?.let { put(f.entry.documentId, it) }
                    own.forEach { m -> groupMembers[m.documentId]?.let { put(m.documentId, it) } }
                }
                Candidate(root, f.entry, f.system, relevant)
            }
    }

    /**
     * Identifies one candidate. A cached game is reused when size and modification time are
     * unchanged (unless [force]). A MANUAL id always survives a rescan. Exceptions never escape:
     * the game is listed with [Game.scanError].
     */
    fun probe(c: Candidate, access: TreeAccess, previous: Game? = null, force: Boolean = false): Game {
        val key = gameKey(c.root.treeUri, c.entry.documentId)
        val relatedIds = c.related.map { it.documentId }
        if (!force && previous != null && previous.sizeBytes == c.entry.size &&
            previous.lastModified == c.entry.lastModified && previous.scanError == null
        ) {
            return previous.copy(relatedDocumentIds = relatedIds, fileName = c.entry.displayName)
        }
        val candidates = c.folderSystem?.let { listOf(it) } ?: SystemClassifier.systemsForExt(c.ext)
        var error: String? = null
        val probe: ProbeResult? = try {
            identify(c.ext, c.entry, c, access, candidates, depth = 0)
        } catch (e: Exception) {
            error = sanitize(e)
            null
        }
        val system = decideSystem(c.folderSystem, probe?.system, candidates, c.ext)
        var id = probe?.id?.let { fitIdToSystem(it, system) } ?: FilenameIds.detect(system, c.entry.displayName)
        if (previous?.id?.method == DetectionMethod.MANUAL) id = previous.id
        val title = SystemClassifier.cleanTitle(c.entry.displayName).ifEmpty { probe?.headerTitle ?: c.entry.displayName }
        return Game(
            key = key,
            title = title,
            fileName = c.entry.displayName,
            system = system,
            treeUri = c.root.treeUri,
            documentId = c.entry.documentId,
            sizeBytes = c.entry.size,
            lastModified = c.entry.lastModified,
            id = id,
            relatedDocumentIds = relatedIds,
            scanError = error,
        )
    }

    /** Sequential scan of several roots. [previous] is the scan cache keyed by game key. */
    fun scanAll(
        roots: List<Pair<RomRoot, TreeAccess>>,
        previous: Map<String, Game> = emptyMap(),
        force: Boolean = false,
    ): ScanResult {
        val games = roots.flatMap { (root, access) ->
            collect(root, access).map { c -> probe(c, access, previous[gameKey(root.treeUri, c.entry.documentId)], force) }
        }
        return ScanResult(games, games.count { it.scanError != null })
    }

    private fun identify(
        ext: String,
        entry: DocEntry,
        c: Candidate,
        access: TreeAccess,
        candidates: List<SystemId>,
        depth: Int,
    ): ProbeResult? {
        if (depth > 2) return null
        return when (ext) {
            "m3u" -> {
                val first = c.groupMembers[entry.documentId]?.firstOrNull() ?: return null
                identify(SystemClassifier.ext(first.displayName), first, c, access, candidates, depth + 1)
            }
            "cue" -> {
                val bin = c.groupMembers[entry.documentId]?.firstOrNull() ?: return null
                access.open(bin.documentId).use { src ->
                    val iso = DiscProbes.openIso(BudgetedByteSource(src)) ?: return null
                    DiscProbes.probeIsoFilesystem(iso)
                }
            }
            "psvita" -> access.open(entry.documentId).use { src ->
                val text = src.read(0, 256).toString(Charsets.UTF_8).trim()
                if (VITA_CONTENT.matches(text)) {
                    ProbeResult(SystemId.PSVITA, DetectedId(text, IdKind.VITA_TITLE_ID, DetectionMethod.CONTAINER_METADATA))
                } else null
            }
            in NO_PROBE -> null
            else -> access.open(entry.documentId).use { src -> probeBytes(ext, BudgetedByteSource(src), candidates) }
        }
    }

    /** Header probes for a single file (PLAN section 6.3). */
    fun probeBytes(ext: String, src: ByteSource, candidates: List<SystemId>): ProbeResult? {
        when (ext) {
            "3ds", "cci", "cxi" -> return CartProbes.probe3ds(src)
            "nds", "dsi" -> return CartProbes.probeNds(src)
            "gba" -> return CartProbes.probeGba(src)
            "z64", "n64", "v64" -> return CartProbes.probeN64(src)
            "pbp" -> return DiscProbes.probePbp(src)
            "gcz" -> return null
        }
        val gcFamily = candidates.any { it == SystemId.GC || it == SystemId.WII }
        if (gcFamily || ext in GC_ONLY) {
            DiscProbes.probeGcWii(src)?.let { return it }
        }
        if (ext in ISO_LIKE) {
            DiscProbes.openIso(src)?.let { iso -> DiscProbes.probeIsoFilesystem(iso)?.let { return it } }
        }
        if (ext in GENESIS_EXT && DiscProbes.isGenesisCart(src)) return ProbeResult(SystemId.GENESIS, null)
        return null
    }

    private fun decideSystem(folder: SystemId?, probed: SystemId?, candidates: List<SystemId>, ext: String): SystemId {
        val gcWii = setOf(SystemId.GC, SystemId.WII)
        if (folder != null) {
            return if (folder in gcWii && probed in gcWii) probed!! else folder
        }
        if (probed != null) return probed
        return candidates.singleOrNull() ?: SystemId.UNKNOWN
    }

    /** Keeps a header id only if it fits the chosen system (PS1/PS2 serials share one format). */
    private fun fitIdToSystem(id: DetectedId, system: SystemId): DetectedId? {
        val wanted = IdValidation.kindFor(system)
        if (wanted == id.kind) return id
        val serials = setOf(IdKind.PS2_SERIAL, IdKind.PSX_SERIAL)
        if (wanted in serials && id.kind in serials) return id.copy(kind = wanted)
        return null
    }

    companion object {
        private const val MAX_TEXT = 64 * 1024
        private val VITA_CONTENT = Regex("^[A-Z]{4}\\d{5}$")
        private val NO_PROBE = setOf("chd", "ecm", "gdi", "cdi", "nsp", "xci", "nca", "nro", "nso", "cia", "3dsx",
            "zcci", "zcxi", "z3dsx", "zip", "7z", "desktop", "dol", "elf", "tgc", "wad")
        private val GC_ONLY = setOf("gcm", "ciso", "rvz", "wia", "wbfs")
        private val ISO_LIKE = setOf("iso", "bin", "img", "cso", "ciso")
        private val GENESIS_EXT = setOf("bin", "md", "gen", "smd")

        /** First 16 hex chars of SHA-256(treeUri + "|" + documentId): stable and not reversible. */
        fun gameKey(treeUri: String, documentId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$treeUri|$documentId".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Error text without URIs or paths (PLAN section 12). */
        fun sanitize(e: Throwable): String {
            val msg = (e.message ?: "").replace(Regex("(content|file)://\\S+"), "<uri>")
                .replace(Regex("/\\S+"), "<path>").take(200)
            return "${e::class.simpleName}${if (msg.isNotBlank()) ": $msg" else ""}"
        }
    }
}
