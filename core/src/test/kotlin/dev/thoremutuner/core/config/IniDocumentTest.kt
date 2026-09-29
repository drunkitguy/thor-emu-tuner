package dev.thoremutuner.core.config

import dev.thoremutuner.core.TestFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN section 15, item 15. */
class IniDocumentTest {
    private val fixtures = listOf(
        "fixtures/dolphin_GMS.ini",
        "fixtures/ppsspp_compat_head.ini",
        "fixtures/retroarch_cfg_head.cfg",
        "fixtures/azahar_config.ini",
    )

    @Test fun realConfigFilesRoundTripByteIdentically() {
        for (f in fixtures) {
            val text = TestFiles.resource(f)
            assertEquals(text, IniDocument.parse(text).serialize(), f)
        }
    }

    @Test fun edgeCasesRoundTrip() {
        for (text in listOf("", "a=1", "a=1\n", "\n\n", "[S]\n", "; c\n[S]\nk = v\n\n", "  [ Spaced ]  \nk=v\n", "k = v with = sign\n")) {
            assertEquals(text, IniDocument.parse(text).serialize(), text)
        }
    }

    @Test fun setPreservesCommentsAndUnrelatedKeys() {
        val text = TestFiles.resource("fixtures/dolphin_GMS.ini")
        val doc = IniDocument.parse(text)
        doc.set("Video_Enhancements", "ForceTextureFiltering", "2")
        val out = doc.serialize()
        assertEquals(text.replace("ForceTextureFiltering = 0", "ForceTextureFiltering = 2"), out)
        assertTrue("# Add action replay cheats here." in out)
        assertEquals("0x00000000", IniDocument.parse(out).get("Video_Hacks", "MissingColorValue"))
    }

    @Test fun keysAreCaseSensitiveAndMayContainBackslashAndDots() {
        val doc = IniDocument.parse("[Renderer]\nresolution_setup\\use_global=false\nrend.Resolution = 480\n")
        assertEquals("false", doc.get("Renderer", "resolution_setup\\use_global"))
        assertEquals("480", doc.get("Renderer", "rend.Resolution"))
        assertNull(doc.get("Renderer", "REND.RESOLUTION"))
        assertNull(doc.get("renderer", "rend.Resolution"))
    }

    @Test fun missingKeyGoesAfterLastKeyOfItsSection() {
        val doc = IniDocument.parse("[A]\nx = 1\ny = 2\n\n[B]\nz = 3\n")
        doc.set("A", "new", "9")
        assertEquals("[A]\nx = 1\ny = 2\nnew = 9\n\n[B]\nz = 3\n", doc.serialize())
    }

    @Test fun missingSectionIsAppended() {
        val doc = IniDocument.parse("[A]\nx = 1\n")
        doc.set("B", "k", "v")
        assertEquals("[A]\nx = 1\n\n[B]\nk = v\n", doc.serialize())
    }

    @Test fun emptySectionMeansTopLevel() {
        val doc = IniDocument.parse("# RetroArch\nvideo_smooth = \"true\"\n")
        assertEquals("\"true\"", doc.get("", "video_smooth"))
        doc.set("", "fps_show", "\"true\"")
        assertEquals("# RetroArch\nvideo_smooth = \"true\"\nfps_show = \"true\"\n", doc.serialize())
    }

    @Test fun separatorIsTheEmulators() {
        val doc = IniDocument.parse(null)
        doc.set("Core", "use_multi_core", "true", separator = "=")
        assertEquals("[Core]\nuse_multi_core=true\n", doc.serialize())
    }

    @Test fun removeKeepsConsistentCrLfAndNormalizesMixedEndings() {
        val doc = IniDocument.parse("[A]\r\nx = 1\r\ny = 2\r\n")
        assertTrue(doc.remove("A", "x"))
        doc.set("A", "z", "3")
        assertEquals("[A]\r\ny = 2\r\nz = 3\r\n", doc.serialize())
        assertEquals("[A]\nx = 1\ny = 2\n", IniDocument.parse("[A]\r\nx = 1\ny = 2\n").serialize())
    }

    @Test fun rejectsMultiLineValues() {
        assertFailsWith<IllegalArgumentException> { IniDocument.parse("").set("A", "k", "v\n[Evil]") }
    }
}
