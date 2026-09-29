package dev.thoremutuner.core.journey

import dev.thoremutuner.core.TestFiles
import dev.thoremutuner.core.config.ApplyPlanner
import dev.thoremutuner.core.config.ConfigWriters
import dev.thoremutuner.core.launch.IntentExtra
import dev.thoremutuner.core.launch.LaunchInputs
import dev.thoremutuner.core.launch.LaunchPlan
import dev.thoremutuner.core.launch.LaunchPlanner
import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.preset.ExtraType
import dev.thoremutuner.core.preset.LaunchFlag
import dev.thoremutuner.core.preset.PresetRepository
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.profile.ProfileService
import dev.thoremutuner.core.profile.SettingValue
import dev.thoremutuner.core.scan.FakeTree
import dev.thoremutuner.core.scan.Fixtures
import dev.thoremutuner.core.scan.Scanner
import dev.thoremutuner.core.store.InMemoryJsonStore
import dev.thoremutuner.core.store.ProfileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End-to-end through :core, as the app drives it: scan a fake SAF tree, detect ids, pick the
 * baseline, make one edit, save rev 2, render the config, and plan the launch intent.
 */
class UserJourneyTest {
    private val repo = PresetRepository()

    @Test fun pspAndDolphinJourney() = runBlocking {
        val tree = FakeTree()
            .add("psp/Synthetic Quest (USA).iso", Fixtures.pspIsoUmd("ULUS-10041"))
            .add("gc/Synthetic Sunshine (USA).iso", Fixtures.gcIso("GMSE01"))
        val games = Scanner().scanAll(listOf(tree.root() to tree)).games.associateBy { it.system }
        val psp = games.getValue(SystemId.PSP)
        val gc = games.getValue(SystemId.GC)
        assertEquals("ULUS10041", psp.id!!.value)
        assertEquals("GMSE01", gc.id!!.value)

        val store = InMemoryJsonStore()
        val profiles = ProfileRepository(store)

        for ((game, emu, edit, golden) in listOf(
            Quad(psp, "ppsspp", SettingValue("Graphics", "InternalResolution", "5"), "journey_ppsspp_rev2.ini"),
            Quad(gc, "dolphin", SettingValue("Video_Enhancements", "MaxAnisotropy", "4"), "journey_dolphin_rev2.ini"),
        )) {
            val def = repo.emulatorsFor(game.system).first()
            assertEquals(emu, def.emulatorId)
            // Choose baseline -> rev 1
            val baseline = PresetResolver.resolve(def, "thor-balanced")
            profiles.update(game.key) { ProfileService.addRevision(it, game.key, emu, "thor-balanced", baseline, "Baseline: Thor Balanced", 1).first }
            // One validated user edit -> rev 2
            val setting = def.setting(edit.section, edit.key)!!
            assertIs<dev.thoremutuner.core.preset.ValueCheck.Ok>(PresetResolver.validate(def, setting, edit.value))
            val rev2Values = PresetResolver.applyEdits(def, baseline, listOf(edit))
            val updated = profiles.update(game.key) {
                ProfileService.addRevision(it, game.key, emu, "thor-balanced", rev2Values, "Sharper", 2, setOf(edit.ref)).first
            }
            val rev2 = updated.single { it.emulatorId == emu }.latest!!
            assertEquals(2, rev2.rev)
            assertEquals(setOf(edit.ref), PresetResolver.changedRefs(baseline, rev2.values))
            // Render (the app writes this through SAF after a backup, then reads it back).
            val ctx = ApplyPlanner.context(def, game, rev2)
            val out = ConfigWriters.forEmulator(def)!!.render(null, rev2.values, ctx)
            assertEquals(TestFiles.resource("golden/$golden"), out.text)
        }

        // Launch intents for both games.
        val romUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/x"
        val pspDef = repo.emulator("ppsspp")!!
        val pspPlan = assertIs<LaunchPlan.Ready>(LaunchPlanner.plan(pspDef, pspDef.launchTargets()[0], LaunchInputs(romUri = romUri, documentId = psp.documentId)))
        assertEquals("org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity", "${pspPlan.primary.packageName}/${pspPlan.primary.className}")
        assertEquals("android.intent.action.VIEW", pspPlan.primary.action)
        assertEquals(romUri, pspPlan.primary.data)
        assertEquals(setOf(LaunchFlag.NEW_TASK, LaunchFlag.GRANT_READ_URI), pspPlan.primary.flags)

        val dolphin = repo.emulator("dolphin")!!
        val dPlan = assertIs<LaunchPlan.Ready>(LaunchPlanner.plan(dolphin, dolphin.launchTargets()[0], LaunchInputs(romUri = romUri, documentId = gc.documentId)))
        assertEquals("org.dolphinemu.dolphinemu.ui.main.TvMainActivity", dPlan.primary.className)
        assertEquals(listOf(IntentExtra("AutoStartFile", ExtraType.STRING, romUri)), dPlan.primary.extras)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
