package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** PLAN section 15, items 10 and 11: every supported format with a synthetic fixture. */
class ProbeTest {
    private val scanner = Scanner()

    private fun probe(ext: String, bytes: ByteArray, vararg cands: SystemId): ProbeResult? =
        scanner.probeBytes(ext, BudgetedByteSource(ArrayByteSource(bytes)), cands.toList())

    @Test fun gameCubeIso() {
        val r = assertNotNull(probe("iso", Fixtures.gcIso("GMSE01"), SystemId.GC, SystemId.WII, SystemId.PS2, SystemId.PSX, SystemId.PSP))
        assertEquals(SystemId.GC, r.system)
        assertEquals("GMSE01", r.id!!.value)
        assertEquals(IdKind.GC_WII_ID6, r.id!!.kind)
        assertEquals(DetectionMethod.HEADER, r.id!!.method)
        assertEquals("SYNTHETIC GC", r.headerTitle)
    }

    @Test fun wiiIso() {
        val r = assertNotNull(probe("iso", Fixtures.wiiIso("RMGE01"), SystemId.WII))
        assertEquals(SystemId.WII, r.system)
        assertEquals("RMGE01", r.id!!.value)
    }

    @Test fun wbfs() {
        val r = assertNotNull(probe("wbfs", Fixtures.wbfs("RSBE01")))
        assertEquals(SystemId.WII, r.system)
        assertEquals("RSBE01", r.id!!.value)
        assertEquals(DetectionMethod.CONTAINER_METADATA, r.id!!.method)
    }

    @Test fun rvzAndWia() {
        val gc = assertNotNull(probe("rvz", Fixtures.rvz("GZLE01", wii = false)))
        assertEquals(SystemId.GC, gc.system); assertEquals("GZLE01", gc.id!!.value)
        val wii = assertNotNull(probe("wia", Fixtures.rvz("RSPE01", wii = true, magic = "WIA")))
        assertEquals(SystemId.WII, wii.system); assertEquals("RSPE01", wii.id!!.value)
    }

    @Test fun gameCubeCiso() {
        val r = assertNotNull(probe("ciso", Fixtures.gcCiso("GALE01")))
        assertEquals(SystemId.GC, r.system)
        assertEquals("GALE01", r.id!!.value)
    }

    @Test fun gczIsRecognizedButNotDecodedInV1() {
        assertNull(probe("gcz", Fixtures.gcz(), SystemId.GC))
        assert(DiscProbes.isGcz(Fixtures.gcz()))
    }

    @Test fun pspIsoViaUmdData() {
        val r = assertNotNull(probe("iso", Fixtures.pspIsoUmd("ULUS-10041"), SystemId.PSP))
        assertEquals(SystemId.PSP, r.system)
        assertEquals("ULUS10041", r.id!!.value)
        assertEquals(IdKind.PSP_DISC_ID, r.id!!.kind)
    }

    @Test fun pspIsoViaParamSfo() {
        val r = assertNotNull(probe("iso", Fixtures.pspIsoSfo("ULES00151"), SystemId.PSP))
        assertEquals(SystemId.PSP, r.system)
        assertEquals("ULES00151", r.id!!.value)
        assertEquals("Synthetic PSP", r.headerTitle)
    }

    @Test fun pspCso() {
        val cso = Fixtures.cso(Fixtures.pspIsoUmd("NPJH50465"))
        val r = assertNotNull(probe("cso", cso, SystemId.PSP, SystemId.PS2))
        assertEquals(SystemId.PSP, r.system)
        assertEquals("NPJH50465", r.id!!.value)
    }

    @Test fun pspCsoWithAllBlocksCompressed() {
        val cso = Fixtures.cso(Fixtures.pspIsoSfo("ULUS10336"), forcePlainFirstData = false)
        assertEquals("ULUS10336", probe("cso", cso, SystemId.PSP)!!.id!!.value)
    }

    @Test fun csoVersusCisoDisambiguation() {
        val pspCso = Fixtures.cso(Fixtures.pspIsoUmd("ULUS-10041"))
        val gcCiso = Fixtures.gcCiso("GALE01")
        // Same "CISO" magic; header_size 0x18 at offset 4 means PSP CSO.
        assert(CsoReader.isPspCso(pspCso))
        assert(!CsoReader.isPspCso(gcCiso))
        // Probed with the other system's extension, each still resolves to its real system.
        assertEquals(SystemId.PSP, probe("ciso", pspCso, SystemId.GC)!!.system)
        assertEquals(SystemId.GC, probe("cso", gcCiso, SystemId.GC)!!.system)
        assertNull(CsoReader.open(ArrayByteSource(gcCiso)))
    }

    @Test fun pbpPspAndPs1() {
        val psp = assertNotNull(probe("pbp", Fixtures.pbp("NPUH10117")))
        assertEquals(SystemId.PSP, psp.system); assertEquals("NPUH10117", psp.id!!.value)
        val ps1 = assertNotNull(probe("pbp", Fixtures.pbp("SLUS00594", category = "ME")))
        assertEquals(SystemId.PSX, ps1.system); assertEquals("SLUS-00594", ps1.id!!.value)
    }

    @Test fun ps2IsoBoot2() {
        val r = assertNotNull(probe("iso", Fixtures.ps2Iso(), SystemId.PS2))
        assertEquals(SystemId.PS2, r.system)
        assertEquals("SLUS-20312", r.id!!.value)
        assertEquals(IdKind.PS2_SERIAL, r.id!!.kind)
    }

    @Test fun ps1RawBinMode2() {
        val bin = Fixtures.raw2352(Fixtures.ps1Iso(), mode = 2)
        val r = assertNotNull(probe("bin", bin, SystemId.PSX))
        assertEquals(SystemId.PSX, r.system)
        assertEquals("SCUS-94163", r.id!!.value)
    }

    @Test fun ps1RawBinMode1() {
        val bin = Fixtures.raw2352(Fixtures.ps1Iso("cdrom:\\SLES_123.45;1"), mode = 1)
        assertEquals("SLES-12345", probe("bin", bin, SystemId.PSX)!!.id!!.value)
    }

    @Test fun n3dsCciWithProductCode() {
        val r = assertNotNull(probe("3ds", Fixtures.cci(0x0004000000055D00L, "CTR-P-AAAE")))
        assertEquals(SystemId.N3DS, r.system)
        assertEquals("0004000000055D00", r.id!!.value)
        assertEquals("CTR-P-AAAE", r.id!!.extra["productCode"])
    }

    @Test fun n3dsCxi() {
        val r = assertNotNull(probe("cxi", Fixtures.cxi()))
        assertEquals("000400000F700000", r.id!!.value)
        assertEquals("CTR-P-CTAP", r.id!!.extra["productCode"])
    }

    @Test fun nds() {
        val r = assertNotNull(probe("nds", Fixtures.nds(code = "ADAE")))
        assertEquals(SystemId.NDS, r.system); assertEquals("ADAE", r.id!!.value)
        assertEquals("SYNTH DS", r.headerTitle)
    }

    @Test fun gba() {
        val r = assertNotNull(probe("gba", Fixtures.gba(code = "BPEE")))
        assertEquals(SystemId.GBA, r.system); assertEquals("BPEE", r.id!!.value)
    }

    @Test fun n64AllThreeByteOrders() {
        for ((ext, bytes, order) in listOf(
            Triple("z64", Fixtures.n64z64(), "z64"),
            Triple("v64", Fixtures.n64v64(), "v64"),
            Triple("n64", Fixtures.n64n64(), "n64"),
        )) {
            val r = assertNotNull(probe(ext, bytes), ext)
            assertEquals(SystemId.N64, r.system)
            assertEquals("NSME", r.id!!.value, ext)
            assertEquals("SYNTH N64", r.headerTitle, ext)
            assertEquals(order, r.id!!.extra["byteOrder"])
        }
    }

    @Test fun switchFilenameTitleIds() {
        assertEquals("01007EF00011E000", SwitchProbe.titleIdFromFileName("Zelda [01007EF00011E000][v0].nsp"))
        // Update -> base application id
        assertEquals("01007EF00011E000", SwitchProbe.titleIdFromFileName("Zelda Update [01007EF00011E800][v65536].nsp"))
        // DLC ids are ignored
        assertNull(SwitchProbe.titleIdFromFileName("Zelda DLC [01007EF00011F001][v0].nsp"))
        assertEquals("0100ABCD12340000", SwitchProbe.titleIdFromFileName("x [0100abcd12340000].xci"))
    }

    @Test fun psVitaFileViaScanner() {
        val tree = FakeTree().add("psvita/Game (USA).psvita", "PCSE00001\n")
        val game = scanner.scanAll(listOf(tree.root() to tree)).games.single()
        assertEquals(SystemId.PSVITA, game.system)
        assertEquals("PCSE00001", game.id!!.value)
        assertEquals(IdKind.VITA_TITLE_ID, game.id!!.kind)
    }

    @Test fun genesisBinDetectedOutsideFolders() {
        assertEquals(SystemId.GENESIS, probe("bin", Fixtures.genesis(), SystemId.PS2, SystemId.PSX, SystemId.GENESIS)!!.system)
    }

    @Test fun systemCnfParsing() {
        assertEquals(SystemId.PS2 to "SLUS-20312", DiscProbes.parseSystemCnf("BOOT2 = cdrom0:\\SLUS_203.12;1\nVER = 1.00"))
        assertEquals(SystemId.PSX to "SCUS-94163", DiscProbes.parseSystemCnf("BOOT = cdrom:\\SCUS_941.63;1"))
        assertEquals(SystemId.PSX to "SLPS-01234", DiscProbes.parseSystemCnf("BOOT=cdrom:SLPS_012.34;1"))
        assertNull(DiscProbes.parseSystemCnf("VER = 1.00"))
    }

    @Test fun garbageIsNotMisidentified() {
        val junk = ByteArray(0x10000) { (it * 31 + 7).toByte() }
        assertNull(probe("iso", junk, SystemId.GC, SystemId.WII, SystemId.PS2, SystemId.PSX, SystemId.PSP))
        assertNull(probe("3ds", junk))
        assertNull(probe("pbp", junk))
        assertNull(probe("z64", junk))
    }
}
