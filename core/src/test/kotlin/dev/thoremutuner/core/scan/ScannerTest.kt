package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerTest {
    private val scanner = Scanner()

    private fun scan(tree: FakeTree) = scanner.scanAll(listOf(tree.root() to tree)).games

    @Test fun cueHidesBinAndIdentifiesPs1RawBin() {
        val tree = FakeTree()
            .add("psx/Game (USA).cue", "FILE \"Game (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")
            .add("psx/Game (USA).bin", Fixtures.raw2352(Fixtures.ps1Iso()))
        val games = scan(tree)
        assertEquals(1, games.size, "the .bin must be hidden behind its .cue")
        val g = games.single()
        assertEquals("Game (USA).cue", g.fileName)
        assertEquals(SystemId.PSX, g.system)
        assertEquals("SCUS-94163", g.id!!.value)
        assertEquals(IdKind.PSX_SERIAL, g.id!!.kind)
        assertEquals(listOf("primary:ROMs/psx/Game (USA).bin"), g.relatedDocumentIds)
    }

    @Test fun cueWithoutFolderIsProbedToPsx() {
        val tree = FakeTree()
            .add("misc/Game.cue", "FILE \"Game.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            .add("misc/Game.bin", Fixtures.raw2352(Fixtures.ps1Iso("cdrom:\\SLUS_005.94;1")))
        val g = scan(tree).single()
        assertEquals(SystemId.PSX, g.system)
        assertEquals("SLUS-00594", g.id!!.value)
    }

    @Test fun m3uHidesDiscsAndTheirTracks() {
        val tree = FakeTree()
            .add("psx/Saga.m3u", "Saga (Disc 1).cue\nSaga (Disc 2).cue\n")
            .add("psx/Saga (Disc 1).cue", "FILE \"Saga (Disc 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            .add("psx/Saga (Disc 1).bin", Fixtures.raw2352(Fixtures.ps1Iso("cdrom:\\SLUS_008.92;1")))
            .add("psx/Saga (Disc 2).cue", "FILE \"Saga (Disc 2).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            .add("psx/Saga (Disc 2).bin", Fixtures.raw2352(Fixtures.ps1Iso("cdrom:\\SLUS_009.08;1")))
        val games = scan(tree)
        assertEquals(listOf("Saga.m3u"), games.map { it.fileName })
        val g = games.single()
        assertEquals("SLUS-00892", g.id!!.value, "id comes from the first disc")
        assertEquals("Saga", g.title)
        assertEquals(2, g.relatedDocumentIds.size)
    }

    @Test fun m3uInSubfolderReferences() {
        val tree = FakeTree()
            .add("ps2/Multi.m3u", ".hidden/Multi (Disc 1).iso\n.hidden/Multi (Disc 2).iso\n")
            .add("ps2/.hidden/Multi (Disc 1).iso", Fixtures.ps2Iso("cdrom0:\\SLUS_212.34;1"))
            .add("ps2/.hidden/Multi (Disc 2).iso", Fixtures.ps2Iso("cdrom0:\\SLUS_212.35;1"))
        val g = scan(tree).single()
        assertEquals(SystemId.PS2, g.system)
        assertEquals("SLUS-21234", g.id!!.value)
    }

    @Test fun gdiHidesTracks() {
        val tree = FakeTree()
            .add("dreamcast/Game.gdi", "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n")
            .add("dreamcast/track01.bin", ByteArray(64))
            .add("dreamcast/track 02.raw", ByteArray(64))
            .add("dreamcast/track03.bin", ByteArray(64))
        val games = scan(tree)
        assertEquals(listOf("Game.gdi"), games.map { it.fileName })
        assertEquals(SystemId.DREAMCAST, games.single().system)
        assertEquals(3, games.single().relatedDocumentIds.size)
    }

    @Test fun folderNamesDecideSystemAndHeadersProvideIds() {
        val tree = FakeTree()
            .add("gc/Sunshine.iso", Fixtures.gcIso("GMSE01"))
            .add("gc/Galaxy.iso", Fixtures.wiiIso("RMGE01")) // Wii disc misfiled in gc/: header wins within the family
            .add("psp/Game.iso", Fixtures.pspIsoUmd("ULUS-10041"))
            .add("ps2/Game.iso", Fixtures.ps2Iso())
            .add("3ds/Game.3ds", Fixtures.cci())
            .add("nds/Game.nds", Fixtures.nds())
            .add("gba/Game.gba", Fixtures.gba())
            .add("n64/Game.v64", Fixtures.n64v64())
            .add("switch/Game [0100000000010000][v0].nsp", ByteArray(32))
            .add("snes/Game (USA).sfc", ByteArray(512))
        val bySystem = scan(tree).associateBy { it.system }
        assertEquals("GMSE01", bySystem.getValue(SystemId.GC).id!!.value)
        assertEquals("RMGE01", bySystem.getValue(SystemId.WII).id!!.value)
        assertEquals("ULUS10041", bySystem.getValue(SystemId.PSP).id!!.value)
        assertEquals("SLUS-20312", bySystem.getValue(SystemId.PS2).id!!.value)
        assertEquals("0004000000055D00", bySystem.getValue(SystemId.N3DS).id!!.value)
        assertEquals("ADAE", bySystem.getValue(SystemId.NDS).id!!.value)
        assertEquals("BPEE", bySystem.getValue(SystemId.GBA).id!!.value)
        assertEquals("NSME", bySystem.getValue(SystemId.N64).id!!.value)
        val sw = bySystem.getValue(SystemId.SWITCH).id!!
        assertEquals("0100000000010000", sw.value); assertEquals(DetectionMethod.FILENAME, sw.method)
        assertNull(bySystem.getValue(SystemId.SNES).id)
        assertEquals("Game", bySystem.getValue(SystemId.SNES).title)
    }

    @Test fun extensionsAndProbesWithoutSystemFolders() {
        val tree = FakeTree()
            .add("all/a.rvz", Fixtures.rvz("GZLE01"))
            .add("all/b.iso", Fixtures.pspIsoSfo("ULES00151"))
            .add("all/c.nds", Fixtures.nds(code = "AMCE"))
            .add("all/d.gbc", ByteArray(512))
            .add("all/e.chd", ByteArray(512))
            .add("all/notes.txt", "hello")
            .add("all/f.zip", ByteArray(10))
        val games = scan(tree).associateBy { it.fileName }
        assertEquals(SystemId.GC, games.getValue("a.rvz").system)
        assertEquals(SystemId.PSP, games.getValue("b.iso").system)
        assertEquals(SystemId.NDS, games.getValue("c.nds").system)
        assertEquals(SystemId.GBC, games.getValue("d.gbc").system)
        assertEquals(SystemId.UNKNOWN, games.getValue("e.chd").system, "ambiguous and not probe-able -> Unrecognized")
        assertTrue("notes.txt" !in games)
        assertTrue("f.zip" !in games, "archives count only inside system folders")
    }

    @Test fun archivesAcceptedInSystemFoldersWithFilenameIds() {
        val tree = FakeTree().add("psp/Game [ULUS-10041].zip", ByteArray(10))
        val g = scan(tree).single()
        assertEquals(SystemId.PSP, g.system)
        assertEquals("ULUS10041", g.id!!.value)
        assertEquals(DetectionMethod.FILENAME, g.id!!.method)
    }

    @Test fun hiddenFilesSkippedFoldersAndDepthLimit() {
        val tree = FakeTree()
            .add("gba/.Hidden.gba", Fixtures.gba())
            .add("gba/media/Shot.gba", Fixtures.gba())
            .add("gba/bios/gba_bios.gba", Fixtures.gba())
            .add("gba/Visible.gba", Fixtures.gba())
            .add("a/b/c/gba/Deep.gba", Fixtures.gba()) // depth 5: skipped
            .add("a/b/gba/Ok.gba", Fixtures.gba()) // depth 4: included
        val names = scan(tree).map { it.fileName }.toSet()
        assertEquals(setOf("Visible.gba", "Ok.gba"), names)
    }

    @Test fun rootFolderNameCountsAsSystemFolder() {
        val tree = FakeTree("primary:ROMs/psp").add("Game.iso", Fixtures.pspIsoUmd())
        assertEquals(SystemId.PSP, scan(tree).single().system)
    }

    @Test fun filenameFallbackWhenHeaderMissing() {
        val tree = FakeTree().add("gc/Zelda [GZLE01].iso", ByteArray(0x1000))
        val g = scan(tree).single()
        assertEquals("GZLE01", g.id!!.value)
        assertEquals(DetectionMethod.FILENAME, g.id!!.method)
        assertEquals("Zelda", g.title)
    }

    @Test fun oneBadFileNeverAbortsTheScan() {
        val broken = object : TreeAccess {
            val inner = FakeTree().add("psp/Bad.iso", Fixtures.pspIsoUmd()).add("psp/Good.iso", Fixtures.pspIsoUmd("ULES-00001"))
            override fun children(dirDocumentId: String) = inner.children(dirDocumentId)
            override fun open(documentId: String): ByteSource =
                if (documentId.endsWith("Bad.iso")) throw java.io.IOException("cannot open content://x/primary%3AROMs%2Fpsp%2FBad.iso")
                else inner.open(documentId)
        }
        val result = scanner.scanAll(listOf(broken.inner.root() to broken))
        assertEquals(2, result.games.size)
        assertEquals(1, result.errors)
        val bad = result.games.first { it.fileName == "Bad.iso" }
        assertNotNull(bad.scanError)
        assertTrue("content://" !in bad.scanError!! && "primary" !in bad.scanError!!, bad.scanError)
        assertEquals("ULES00001", result.games.first { it.fileName == "Good.iso" }.id!!.value)
    }

    @Test fun cacheReusesUnchangedFilesAndForceReprobes() {
        val tree = FakeTree().add("gc/Game.iso", Fixtures.gcIso())
        val first = scanner.scanAll(listOf(tree.root() to tree)).games
        tree.opened.clear()
        val second = scanner.scanAll(listOf(tree.root() to tree), first.associateBy { it.key }).games
        assertEquals(first, second)
        assertTrue(tree.opened.isEmpty(), "unchanged files are not re-probed")
        scanner.scanAll(listOf(tree.root() to tree), first.associateBy { it.key }, force = true)
        assertTrue(tree.opened.isNotEmpty(), "Rescan forces a probe")
    }

    @Test fun manualIdSurvivesRescan() {
        val tree = FakeTree().add("gc/Game.iso", Fixtures.gcIso("GMSE01"))
        val first = scan(tree).single()
        val manual = first.copy(id = DetectedId("GMSP01", IdKind.GC_WII_ID6, DetectionMethod.MANUAL))
        val again = scanner.scanAll(listOf(tree.root() to tree), mapOf(manual.key to manual), force = true).games.single()
        assertEquals("GMSP01", again.id!!.value)
        assertEquals(DetectionMethod.MANUAL, again.id!!.method)
    }

    @Test fun gameKeyIsStableAndNotReversible() {
        val k1 = Scanner.gameKey("content://tree/primary%3AROMs", "primary:ROMs/gc/Game.iso")
        val k2 = Scanner.gameKey("content://tree/primary%3AROMs", "primary:ROMs/gc/Game.iso")
        assertEquals(k1, k2)
        assertEquals(16, k1.length)
        assertTrue(k1.all { it in "0123456789abcdef" })
        assertTrue("Game" !in k1)
    }

    @Test fun titleCleaning() {
        assertEquals("Metroid Prime", SystemClassifier.cleanTitle("Metroid Prime (USA) (Rev 2) [!].iso"))
        assertEquals("Final Fantasy VII", SystemClassifier.cleanTitle("Final Fantasy VII (Disc 1).cue"))
    }
}
