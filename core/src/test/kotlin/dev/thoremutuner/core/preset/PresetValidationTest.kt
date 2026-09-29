package dev.thoremutuner.core.preset

import dev.thoremutuner.core.TestFiles
import dev.thoremutuner.core.model.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** PLAN section 15, items 6-9, plus the evidence-honesty rule. */
class PresetValidationTest {
    private val repo = PresetRepository()
    private val sources: String by lazy { TestFiles.file("docs/SOURCES.md").readText() }

    @Test
    fun indexListsThirteenFilesThatAllParseWithUniqueIds() {
        assertEquals(13, repo.files.size)
        assertEquals(13, repo.emulators.size)
        val ids = repo.emulators.map { it.emulatorId }
        assertEquals(ids.size, ids.toSet().size, "duplicate emulator ids: $ids")
    }

    @Test
    fun exactlyTheFiveV1EmulatorsAreFull() {
        val full = repo.emulators.filter { it.supportLevel == SupportLevel.FULL }.map { it.emulatorId }.toSet()
        assertEquals(setOf("dolphin", "ppsspp", "eden", "azahar", "retroarch"), full)
    }

    @Test
    fun everyPresetValueHasTraceableSourceAndKeySource() {
        val problems = mutableListOf<String>()
        for (def in repo.emulators) {
            val values = def.presets.flatMap { p -> p.values.map { "${def.emulatorId}/${p.id}" to it } } +
                def.globalRecommendations.map { "${def.emulatorId}/globalRecommendations" to it }
            for ((where, v) in values) {
                val tag = "$where ${v.section}/${v.key}"
                if (v.source.isBlank()) problems += "$tag: empty source"
                if (v.isInferred) {
                    if (v.reason.isNullOrBlank()) problems += "$tag: inferred without reason"
                } else if (!inSources(v.source)) {
                    problems += "$tag: source not in SOURCES.md: ${v.source}"
                }
                val ks = v.keySource
                if (ks.isNullOrBlank()) problems += "$tag: missing keySource"
                else if (!inSources(ks)) problems += "$tag: keySource not in SOURCES.md: $ks"
            }
            for (fix in def.gameSpecific) for (v in fix.values) {
                if (!inSources(v.source)) problems += "${def.emulatorId} gameSpecific ${fix.title}: ${v.source}"
            }
            for (s in def.settings) {
                if (!inSources(s.keySource)) problems += "${def.emulatorId} setting ${s.key}: keySource ${s.keySource}"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun fullEmulatorPresetValuesExistInCatalogAndAreValid() {
        val problems = mutableListOf<String>()
        for (def in repo.emulators.filter { it.isFull }) {
            for (p in def.presets) for (v in p.values) {
                val setting = def.setting(v.section, v.key)
                if (setting == null) {
                    problems += "${def.emulatorId}/${p.id}: ${v.section}/${v.key} not in settings[]"
                    continue
                }
                when (val check = PresetResolver.validate(def, setting, v.value)) {
                    is ValueCheck.Invalid -> problems += "${def.emulatorId}/${p.id}: ${v.key}=${v.value}: ${check.message}"
                    is ValueCheck.Ok -> Unit
                }
                if (setting.type == SettingType.ENUM) {
                    assertTrue(setting.options.any { it.value == v.value }, "${def.emulatorId} ${v.key}=${v.value}")
                }
            }
            for (s in def.settings) {
                if (s.type == SettingType.ENUM && s.options.isEmpty()) problems += "${def.emulatorId} ${s.key}: enum without options"
                val d = s.defaultValue
                if (d != null && s.type == SettingType.ENUM && s.options.none { it.value == d }) {
                    problems += "${def.emulatorId} ${s.key}: default $d not an option"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun noGenericDolphinPresetTouchesVideoHacks() {
        val dolphin = repo.emulator("dolphin")!!
        for (p in dolphin.presets) {
            assertTrue(p.values.none { it.section == "Video_Hacks" }, "preset ${p.id} contains a Video_Hacks key")
        }
    }

    @Test
    fun presetEvidenceIsNeverStrongerThanItsWeakestValue() {
        val problems = repo.emulators.flatMap { def ->
            def.presets.filter { p -> p.values.any { it.isInferred } && p.evidence != Evidence.INFERRED }
                .map { "${def.emulatorId}/${it.id} claims ${it.evidence} but has inferred values" }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        for (def in repo.emulators) for (p in def.presets) {
            assertEquals(p.evidence, PresetResolver.effectiveEvidence(p))
        }
    }

    @Test
    fun inferredBadgeNeverClaimsTesting() {
        assertTrue("tested" !in Evidence.INFERRED.badge.lowercase())
        assertTrue("unverified" in Evidence.INFERRED.badge.lowercase())
    }

    @Test
    fun referenceEmulatorsHaveManualChecklistFields() {
        for (def in repo.emulators.filter { !it.isFull }) {
            assertEquals(ConfigMode.MANUAL, def.configTarget.mode, def.emulatorId)
            for (p in def.presets) for (v in p.values) {
                assertTrue(!v.uiPath.isNullOrBlank() && !v.uiValue.isNullOrBlank(), "${def.emulatorId}/${p.id} ${v.key}")
            }
        }
    }

    @Test
    fun everyEmulatorSystemIsKnownOrExplicitlyOutOfScope() {
        val outOfScope = setOf("naomi", "atomiswave")
        for (def in repo.emulators) for (s in def.systems) {
            assertTrue(SystemId.fromId(s) != null || s in outOfScope, "${def.emulatorId}: unknown system $s")
        }
    }

    @Test
    fun retroArchCoversEverySystemItLists() {
        val ra = repo.emulator("retroarch")!!
        for (s in ra.systems) {
            assertTrue(ra.configTarget.cores.any { s in it.systems }, "no RetroArch core for $s")
        }
    }

    @Test
    fun launchTargetsAreDedupedPairs() {
        val armsx2 = repo.emulator("armsx2")!!
        val targets = armsx2.launchTargets()
        assertEquals(targets.size, targets.map { it.packageName to it.activity }.toSet().size)
        assertEquals(listOf("come.nanodata.armsx2", "com.armsx2", "com.armsx2.nightly"), armsx2.distinctPackageNames)
        val flycast = repo.emulator("flycast")!!
        assertEquals(listOf("com.flycast.emulator"), flycast.distinctPackageNames)
        assertEquals(2, flycast.launchTargets().size)
    }

    /** Whole-URL match: a prefix of a longer listed URL does not count. */
    private fun inSources(url: String): Boolean =
        url.startsWith("http") && Regex("(?<![\\w/.%-])" + Regex.escape(url) + "(?![\\w/.%-])").containsMatchIn(sources)

    @Test
    fun urlMatchingIsWholeUrl() {
        assertTrue(inSources("https://raw.githubusercontent.com/libretro/RetroArch/master/config.def.h"))
        assertTrue(!inSources("https://raw.githubusercontent.com/libretro/RetroArch/master/config"))
        assertTrue(!inSources("https://raw.githubusercontent.com/libretro/RetroArch/master/retroarch.cf"))
    }
}
