package dev.thoremutuner.core.config

import dev.thoremutuner.core.TestFiles
import dev.thoremutuner.core.preset.PresetRepository
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.profile.SettingValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLAN section 15, items 16-20: golden files from "Thor Balanced" (RetroArch: "Thor Low Latency"). */
class WriterGoldenTest {
    private val repo = PresetRepository()
    private fun def(id: String) = repo.emulator(id)!!
    private fun golden(name: String) = TestFiles.resource("golden/$name")

    @Test fun dolphinNewFile() {
        val d = def("dolphin")
        val values = PresetResolver.resolve(d, "thor-balanced")
        val r = DolphinWriter.render(null, values, WriteContext(d, gameId = "GMSE01"))
        assertEquals("GameSettings/GMSE01.ini", r.relativePath)
        assertEquals(golden("dolphin_thor_balanced_new.ini"), r.text)
        assertTrue(r.refused.isEmpty())
    }

    @Test fun dolphinMergesIntoExistingFileWithoutTouchingHacks() {
        val d = def("dolphin")
        val r = DolphinWriter.render(TestFiles.resource("fixtures/dolphin_GMS.ini"), PresetResolver.resolve(d, "thor-balanced"), WriteContext(d, gameId = "GMSE01"))
        assertEquals(golden("dolphin_thor_balanced_merged_GMS.ini"), r.text)
    }

    @Test fun dolphinRefusesVideoHacksUnlessSetByHand() {
        val d = def("dolphin")
        val hack = SettingValue("Video_Hacks", "EFBToTextureEnable", "True")
        val refused = DolphinWriter.render(null, listOf(hack), WriteContext(d, gameId = "GMSE01"))
        assertEquals(1, refused.refused.size)
        assertEquals(DolphinWriter.HACK_REFUSAL, refused.refused.single().reason)
        assertFalse("Video_Hacks" in refused.text)
        val manual = DolphinWriter.render(null, listOf(hack), WriteContext(d, gameId = "GMSE01", manualRefs = setOf(hack.ref)))
        assertEquals("[Video_Hacks]\nEFBToTextureEnable = True\n", manual.text)
    }

    @Test fun ppssppNewFileNameAndNoBackend() {
        val d = def("ppsspp")
        val values = PresetResolver.resolve(d, "thor-balanced") + SettingValue("Graphics", "GraphicsBackend", "3")
        val r = PpssppWriter.render(null, values, WriteContext(d, gameId = "ULUS10041"))
        assertEquals("PSP/SYSTEM/ULUS10041_ppsspp.ini", r.relativePath)
        assertFalse(r.relativePath.endsWith("_game.ini"))
        assertEquals(golden("ppsspp_thor_balanced_ULUS10041.ini"), r.text)
        assertFalse("GraphicsBackend" in r.text)
        assertEquals(listOf("GraphicsBackend"), r.refused.map { it.value.key })
    }

    @Test fun ppssppMergesAndKeepsUserKeys() {
        val d = def("ppsspp")
        val existing = "[Graphics]\nFrameSkip = 1\nInternalResolution = 2\n"
        val r = PpssppWriter.render(existing, PresetResolver.resolve(d, "thor-balanced"), WriteContext(d, gameId = "ULUS10041"))
        assertTrue(r.text.startsWith("[Graphics]\nFrameSkip = 1\nInternalResolution = 4\n"))
    }

    @Test fun edenInlineIni() {
        val d = def("eden")
        val ini = EdenIniBuilder.build(PresetResolver.resolve(d, "thor-balanced"), d)
        assertEquals(golden("eden_thor_balanced.ini"), ini)
    }

    @Test fun edenEveryKeyHasUseGlobalAndCorrectDefault() {
        val d = def("eden")
        for (preset in d.presets) {
            val values = PresetResolver.resolve(d, preset.id)
            val doc = IniDocument.parse(EdenIniBuilder.build(values, d))
            for (v in values) {
                assertEquals("false", doc.get(v.section, "${v.key}\\use_global"), "${preset.id} ${v.key}")
                val def = d.setting(v.section, v.key)!!.defaultValue
                assertEquals((def != null && def == v.value).toString(), doc.get(v.section, "${v.key}\\default"))
                assertEquals(v.value, doc.get(v.section, v.key))
                if (d.setting(v.section, v.key)!!.type == dev.thoremutuner.core.preset.SettingType.ENUM) {
                    assertTrue(v.value.toIntOrNull() != null, "enum ${v.key} must be an integer")
                }
            }
            // Section placement: use_docked_mode lives in [System], resolution_setup in [Renderer].
            val docked = values.first { it.key == "use_docked_mode" }.value
            assertEquals(docked, doc.get("System", "use_docked_mode"))
            assertEquals(null, doc.get("Renderer", "use_docked_mode"))
            assertEquals(values.first { it.key == "resolution_setup" }.value, doc.get("Renderer", "resolution_setup"))
            assertEquals(null, doc.get("System", "resolution_setup"))
        }
        val doc = IniDocument.parse(EdenIniBuilder.build(PresetResolver.resolve(d, "thor-balanced"), d))
        assertTrue(doc.get("System", "use_docked_mode") != null)
        assertTrue(doc.get("Renderer", "resolution_setup") != null)
    }

    @Test fun edenNullDefaultWritesFalseAndDriverSection() {
        val d = def("eden").let { e -> e.copy(settings = e.settings.map { if (it.key == "backend") it.copy(defaultValue = null) else it }) }
        val ini = EdenIniBuilder.build(listOf(SettingValue("Renderer", "backend", "1")), d, driverFileName = "turnip_v25.zip")
        assertEquals("[Renderer]\nbackend\\use_global=false\nbackend\\default=false\nbackend=1\n\n[GpuDriver]\ndriver_path=turnip_v25.zip\n", ini)
    }

    @Test fun edenSectionOrder() {
        val d = def("eden")
        val values = listOf(
            SettingValue("System", "use_docked_mode", "1"),
            SettingValue("Renderer", "gpu_accuracy", "1"),
            SettingValue("Cpu", "cpu_accuracy", "1"),
            SettingValue("Core", "use_multi_core", "false"),
        )
        val sections = IniDocument.parse(EdenIniBuilder.build(values, d)).sections()
        assertEquals(listOf("Core", "Cpu", "Renderer", "System"), sections)
    }

    @Test fun azaharMergeGoldenAndBooleansNeverNumeric() {
        val d = def("azahar")
        val values = PresetResolver.resolve(d, "thor-balanced")
        val r = AzaharWriter.apply(TestFiles.resource("fixtures/azahar_config.ini"), values, d, AzaharState(), "game1", 1)
        assertEquals(golden("azahar_thor_balanced.ini"), r.render.text)
        val doc = IniDocument.parse(r.render.text)
        for (s in d.settings.filter { it.type == dev.thoremutuner.core.preset.SettingType.BOOL }) {
            val v = doc.get(s.section, s.key) ?: continue
            assertTrue(v == "true" || v == "false", "${s.key} = $v")
        }
        // Even a user edit spelled 1/0 is written as true/false.
        val edited = PresetResolver.applyEdits(d, values, listOf(SettingValue("Renderer", "async_shader_compilation", "0")))
        val r2 = AzaharWriter.apply(r.render.text, edited, d, r.state, "game1", 2)
        assertEquals("false", IniDocument.parse(r2.render.text).get("Renderer", "async_shader_compilation"))
        assertFalse(Regex("async_shader_compilation = [01]\\b").containsMatchIn(r2.render.text))
    }

    @Test fun azaharRestoreReturnsOriginalFileExactly() {
        val d = def("azahar")
        val original = TestFiles.resource("fixtures/azahar_config.ini")
        val a = AzaharWriter.apply(original, PresetResolver.resolve(d, "thor-balanced"), d, AzaharState(), "g1", 1)
        val b = AzaharWriter.apply(a.render.text, PresetResolver.resolve(d, "thor-quality"), d, a.state, "g2", 1)
        val restored = AzaharWriter.restore(b.render.text, d, b.state)
        assertEquals(original, restored.render.text)
        assertEquals(AzaharState(), restored.state)
    }

    @Test fun azaharSwapRestoresKeysTheNextGameDoesNotManage() {
        val d = def("azahar")
        val original = TestFiles.resource("fixtures/azahar_config.ini")
        val a = AzaharWriter.apply(original, PresetResolver.resolve(d, "thor-balanced"), d, AzaharState(), "g1", 1)
        // Game 2 only manages the layout: its render must not inherit game 1's resolution_factor.
        val b = AzaharWriter.apply(a.render.text, PresetResolver.resolve(d, "thor-dual-screen"), d, a.state, "g2", 1)
        val doc = IniDocument.parse(b.render.text)
        assertEquals(null, doc.get("Renderer", "resolution_factor"))
        assertEquals("1", doc.get("Renderer", "graphics_api"))
        assertEquals("1", doc.get("Layout", "layout_option"))
        assertTrue(b.state.isApplied("g2", 1))
        assertFalse(b.state.isApplied("g1", 1))
        // Originals are captured once, on first touch.
        assertEquals("1", b.state.originals.first { it.key == "graphics_api" }.value)
    }

    @Test fun retroArchOverrideGolden() {
        val d = def("retroarch")
        val core = ConfigWriters.coreFor(d, listOf("snes"))!!
        val r = RetroArchOverrideWriter.render(null, PresetResolver.resolve(d, "thor-low-latency"), WriteContext(d, romBaseName = "Super Game (USA)", core = core))
        assertEquals("config/Snes9x/Super Game (USA).cfg", r.relativePath)
        assertEquals(golden("retroarch_thor_low_latency.cfg"), r.text)
        assertTrue(r.warnings.isEmpty())
        val foreign = RetroArchOverrideWriter.render("video_smooth = \"true\"\n", emptyList(), WriteContext(d, romBaseName = "x", core = core))
        assertEquals(1, foreign.warnings.size)
    }

    @Test fun writersRefuseUnknownKeysAndInjection() {
        val d = def("dolphin")
        val r = DolphinWriter.render(null, listOf(SettingValue("Core", "NotAKey", "1"), SettingValue("Core", "GFXBackend", "Vulkan\n[Video_Hacks]")), WriteContext(d, gameId = "GMSE01"))
        assertEquals(2, r.refused.size)
        assertEquals("", r.text)
    }

    @Test fun pathsRequireIdsAndRejectTraversal() {
        val d = def("ppsspp")
        kotlin.test.assertFailsWith<WriterException> { ConfigWriters.relativePath(d, WriteContext(d, gameId = null)) }
        kotlin.test.assertFailsWith<WriterException> { ConfigWriters.relativePath(d, WriteContext(d, gameId = "../x")) }
    }

    @Test fun referenceEmulatorsHaveNoWriter() {
        for (e in repo.emulators) {
            val w = ConfigWriters.forEmulator(e)
            if (e.isFull) assertTrue(w != null, e.emulatorId) else assertEquals(null, w, e.emulatorId)
        }
    }

    @Test fun writersArePure() {
        // Same input twice -> same output; no I/O classes referenced from the config package.
        val d = def("ppsspp")
        val v = PresetResolver.resolve(d, "thor-balanced")
        assertEquals(PpssppWriter.render(null, v, WriteContext(d, gameId = "ULUS10041")), PpssppWriter.render(null, v, WriteContext(d, gameId = "ULUS10041")))
        val sources = TestFiles.file("core/src/main/kotlin/dev/thoremutuner/core/config").listFiles()!!.joinToString("\n") { it.readText() }
        for (io in listOf("java.io.File", "java.nio.file", "FileInputStream", "FileOutputStream")) {
            assertFalse(io in sources, "config package must not do I/O ($io)")
        }
    }
}
