package dev.thoremutuner.core.launch

import dev.thoremutuner.core.preset.ExtraType
import dev.thoremutuner.core.preset.LaunchFlag
import dev.thoremutuner.core.preset.PresetRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLAN section 15, items 21-22. Expected intents for Dolphin, PPSSPP, Eden, RetroArch and
 * NetherSX2 are written out by hand (not derived from the JSON under test).
 */
class LaunchPlannerTest {
    private val repo = PresetRepository()
    private val romUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fx"
    private val inputs = LaunchInputs(
        romUri = romUri,
        documentId = "primary:ROMs/snes/Super Game (USA).sfc",
        titleId = "0100000000010000",
        customSettingsIni = "[Renderer]\nresolution_setup=3\n",
        coreFile = "snes9x_libretro_android.so",
    )

    private fun ready(id: String, pkgIndex: Int = 0, i: LaunchInputs = inputs): LaunchPlan.Ready {
        val def = repo.emulator(id)!!
        return assertIs<LaunchPlan.Ready>(LaunchPlanner.plan(def, def.launchTargets()[pkgIndex], i))
    }

    @Test fun dolphinExactIntent() {
        val expected = IntentSpec(
            packageName = "org.dolphinemu.dolphinemu",
            className = "org.dolphinemu.dolphinemu.ui.main.TvMainActivity",
            action = "android.intent.action.MAIN",
            categories = listOf("android.intent.category.LEANBACK_LAUNCHER"),
            data = romUri,
            mimeType = null,
            extras = listOf(IntentExtra("AutoStartFile", ExtraType.STRING, romUri)),
            flags = setOf(LaunchFlag.NEW_TASK, LaunchFlag.GRANT_READ_URI),
            clipDataUri = null,
        )
        assertEquals(expected, ready("dolphin").primary)
    }

    @Test fun ppssppExactIntent() {
        val expected = IntentSpec(
            packageName = "org.ppsspp.ppsspp",
            className = "org.ppsspp.ppsspp.PpssppActivity",
            action = "android.intent.action.VIEW",
            categories = listOf("android.intent.category.DEFAULT"),
            data = romUri,
            mimeType = null,
            extras = emptyList(),
            flags = setOf(LaunchFlag.NEW_TASK, LaunchFlag.GRANT_READ_URI),
            clipDataUri = null,
        )
        assertEquals(expected, ready("ppsspp").primary)
        assertEquals("org.ppsspp.ppssppgold", ready("ppsspp", 1).primary.packageName)
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", ready("ppsspp", 1).primary.className)
    }

    @Test fun edenCustomConfigIntent() {
        val plan = ready("eden")
        val expected = IntentSpec(
            packageName = "dev.eden.eden_emulator",
            className = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            action = "dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG",
            categories = listOf("android.intent.category.DEFAULT"),
            data = null,
            mimeType = null,
            extras = listOf(
                IntentExtra("title_id", ExtraType.STRING, "0100000000010000"),
                IntentExtra("custom_settings", ExtraType.STRING, "[Renderer]\nresolution_setup=3\n"),
            ),
            flags = setOf(LaunchFlag.NEW_TASK),
            clipDataUri = null,
        )
        assertEquals(expected, plan.primary)
        assertTrue(plan.attempts.first().appliesSettings)
        assertTrue(plan.warnings.isEmpty())
        // The VIEW launch is kept only as a last-resort fallback that does not apply settings.
        val last = plan.attempts.last()
        assertEquals("android.intent.action.VIEW", last.spec.action)
        assertEquals(false, last.appliesSettings)
    }

    @Test fun edenFallsBackToViewWithoutTitleId() {
        val plan = ready("eden", i = inputs.copy(titleId = null))
        val expected = IntentSpec(
            packageName = "dev.eden.eden_emulator",
            className = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            action = "android.intent.action.VIEW",
            categories = listOf("android.intent.category.DEFAULT"),
            data = romUri,
            mimeType = "application/octet-stream",
            extras = emptyList(),
            flags = setOf(LaunchFlag.NEW_TASK, LaunchFlag.GRANT_READ_URI),
            clipDataUri = null,
        )
        assertEquals(expected, plan.primary)
        assertEquals(false, plan.attempts.single().appliesSettings)
        assertEquals(1, plan.warnings.size)
    }

    @Test fun retroArchExactIntent() {
        val expected = IntentSpec(
            packageName = "com.retroarch.aarch64",
            className = "com.retroarch.browser.retroactivity.RetroActivityFuture",
            action = null,
            categories = emptyList(),
            data = null,
            mimeType = null,
            extras = listOf(
                IntentExtra("CONFIGFILE", ExtraType.STRING, "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"),
                IntentExtra("LIBRETRO", ExtraType.STRING, "/data/user/0/com.retroarch.aarch64/cores/snes9x_libretro_android.so"),
                IntentExtra("ROM", ExtraType.STRING, "/storage/emulated/0/ROMs/snes/Super Game (USA).sfc"),
            ),
            flags = setOf(LaunchFlag.NEW_TASK),
            clipDataUri = null,
        )
        assertEquals(expected, ready("retroarch").primary)
    }

    @Test fun retroArchNeedsCoreAndRealPath() {
        val def = repo.emulator("retroarch")!!
        val t = def.launchTargets()[0]
        assertEquals(LaunchPlan.Failed(LaunchError.MissingCore), LaunchPlanner.plan(def, t, inputs.copy(coreFile = null)))
        assertEquals(
            LaunchPlan.Failed(LaunchError.UnsupportedRomPath),
            LaunchPlanner.plan(def, t, inputs.copy(documentId = "msf:12345")),
        )
    }

    @Test fun netherSx2ExactIntent() {
        val expected = IntentSpec(
            packageName = "xyz.aethersx2.android",
            className = "xyz.aethersx2.android.EmulationActivity",
            action = "android.intent.action.MAIN",
            categories = emptyList(),
            data = null,
            mimeType = null,
            extras = listOf(IntentExtra("bootPath", ExtraType.STRING, romUri)),
            flags = setOf(LaunchFlag.NEW_TASK, LaunchFlag.CLEAR_TASK, LaunchFlag.CLEAR_TOP, LaunchFlag.GRANT_READ_URI),
            clipDataUri = romUri, // data is null but an extra carries the URI: grant via ClipData
        )
        assertEquals(expected, ready("nethersx2").primary)
    }

    @Test fun allThirteenEmulatorsResolveEveryTargetWithoutLeftoverPlaceholders() {
        for (def in repo.emulators) {
            val targets = def.launchTargets()
            assertTrue(targets.isNotEmpty(), def.emulatorId)
            for (t in targets) {
                val plan = assertIs<LaunchPlan.Ready>(LaunchPlanner.plan(def, t, inputs), "${def.emulatorId} ${t.packageName}")
                for (a in plan.attempts) {
                    val s = a.spec
                    val text = listOfNotNull(s.packageName, s.className, s.action, s.data) + s.extras.map { it.value }
                    assertTrue(text.none { Regex("\\{[A-Za-z]+}").containsMatchIn(it) }, "${def.emulatorId}: $text")
                    assertEquals(t.packageName, s.packageName)
                    assertTrue(LaunchFlag.NEW_TASK in s.flags)
                }
                // Component, action and data template come straight from the JSON.
                assertEquals(def.launch.action, plan.primary.action.takeIf { def.launch.action != null })
                assertEquals(def.launch.extras.map { it.name }, plan.primary.extras.map { it.name })
            }
        }
    }

    @Test fun packageActivityPairsAreTriedInOrder() {
        val armsx2 = repo.emulator("armsx2")!!
        val targets = armsx2.launchTargets()
        assertEquals(
            listOf(
                "come.nanodata.armsx2" to "com.armsx2.MainActivity",
                "com.armsx2" to "com.armsx2.MainActivity",
                "com.armsx2.nightly" to "com.armsx2.MainActivity",
                "come.nanodata.armsx2" to "kr.co.iefriends.pcsx2.MainActivity",
            ),
            targets.map { it.packageName to it.activity },
        )
        val plan = assertIs<LaunchPlan.Ready>(LaunchPlanner.plan(armsx2, targets[3], inputs))
        assertEquals("kr.co.iefriends.pcsx2.MainActivity", plan.primary.className)
    }

    @Test fun vitaStringArrayAndMelonDualDsForkAction() {
        val vita = ready("vita3k").primary
        assertEquals(listOf("-r", "0100000000010000"), vita.extras.single().values)
        assertEquals(ExtraType.STRING_ARRAY, vita.extras.single().type)
        val melon = ready("melonds") // first package = MelonDualDS
        assertEquals("me.magnum.melonds.LAUNCH_ROM", melon.primary.action)
        assertEquals("me.magnum.melondualds.LAUNCH_ROM", melon.attempts[1].spec.action)
        assertEquals(romUri, melon.primary.clipDataUri)
        assertEquals(1, ready("melonds", 1).attempts.size) // upstream package: no fork fallback
    }

    @Test fun duckStationBoolExtra() {
        val e = ready("duckstation").primary.extras.first { it.name == "resumeState" }
        assertEquals(ExtraType.BOOL, e.type)
        assertEquals("false", e.value)
    }

    @Test fun safPaths() {
        assertEquals("/storage/emulated/0/ROMs/snes/x.sfc", SafPaths.toFilePath("primary:ROMs/snes/x.sfc"))
        assertEquals("/storage/1A2B-3C4D/ROMs/gc/g.rvz", SafPaths.toFilePath("1A2B-3C4D:ROMs/gc/g.rvz"))
        assertEquals("/storage/ABCD-0123/x", SafPaths.toFilePath("abcd-0123:x"))
        assertEquals("/storage/emulated/0", SafPaths.toFilePath("primary:"))
        assertNull(SafPaths.toFilePath("msf:1234"))
        assertNull(SafPaths.toFilePath("raw:/storage/emulated/0/x"))
        assertNull(SafPaths.toFilePath("home:Documents/x"))
        assertNull(SafPaths.toFilePath("primary:ROMs/../../data"))
        assertNull(SafPaths.toFilePath("noColon"))
    }

    @Test fun unsupportedPathIsATypedErrorForWinlator() {
        val def = repo.emulator("winlator")!!
        val plan = LaunchPlanner.plan(def, def.launchTargets()[0], inputs.copy(documentId = "msf:42"))
        assertEquals(LaunchPlan.Failed(LaunchError.UnsupportedRomPath), plan)
    }
}
