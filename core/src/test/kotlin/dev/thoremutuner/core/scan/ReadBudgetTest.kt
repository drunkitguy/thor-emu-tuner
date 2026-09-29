package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PLAN section 15, item 13: probes never read more than 256 KiB (+64 directory sectors). */
class ReadBudgetTest {
    private val scanner = Scanner()
    private val budget = BudgetedByteSource.READ_BUDGET

    private fun countRead(ext: String, bytes: ByteArray, size: Long = bytes.size.toLong(), vararg cands: SystemId): Long {
        val counting = CountingByteSource(SparseByteSource(bytes, size))
        scanner.probeBytes(ext, BudgetedByteSource(counting), cands.toList())
        return counting.total
    }

    @Test fun everyFixtureStaysWithinBudgetEvenOnHugeImages() {
        val huge = 4_700_000_000L // a DVD-sized virtual image
        val cases = listOf(
            Triple("iso", Fixtures.gcIso(), arrayOf(SystemId.GC, SystemId.WII, SystemId.PS2, SystemId.PSX, SystemId.PSP)),
            Triple("iso", Fixtures.wiiIso(), arrayOf(SystemId.WII)),
            Triple("wbfs", Fixtures.wbfs(), arrayOf(SystemId.WII)),
            Triple("rvz", Fixtures.rvz(), arrayOf(SystemId.GC)),
            Triple("ciso", Fixtures.gcCiso(), arrayOf(SystemId.GC)),
            Triple("iso", Fixtures.ps2Iso(), arrayOf(SystemId.PS2)),
            Triple("iso", Fixtures.pspIsoSfo(), arrayOf(SystemId.PSP)),
            Triple("cso", Fixtures.cso(Fixtures.pspIsoUmd()), arrayOf(SystemId.PSP)),
            Triple("bin", Fixtures.raw2352(Fixtures.ps1Iso()), arrayOf(SystemId.PSX)),
            Triple("pbp", Fixtures.pbp(), arrayOf(SystemId.PSP)),
            Triple("3ds", Fixtures.cci(), arrayOf(SystemId.N3DS)),
            Triple("nds", Fixtures.nds(), arrayOf(SystemId.NDS)),
            Triple("gba", Fixtures.gba(), arrayOf(SystemId.GBA)),
            Triple("z64", Fixtures.n64z64(), arrayOf(SystemId.N64)),
            Triple("iso", ByteArray(0), arrayOf(SystemId.GC, SystemId.PS2, SystemId.PSP)), // all zeros
        )
        for ((ext, bytes, cands) in cases) {
            val read = countRead(ext, bytes, huge, *cands)
            assertTrue(read <= budget, "$ext read $read bytes (budget $budget)")
        }
    }

    @Test fun budgetedSourceStopsRunawayReads() {
        val src = BudgetedByteSource(SparseByteSource(ByteArray(0), 10_000_000L))
        assertFailsWith<ReadBudgetExceeded> {
            var off = 0L
            while (true) { src.read(off, 65536); off += 65536 }
        }
        assertTrue(src.bytesRead <= budget)
    }

    @Test fun hostileDirectoryCannotExceedBudget() {
        // A root directory claiming 1 GB of records is capped at 64 sectors.
        val iso = Fixtures.ps2Iso()
        val pvd = 16 * 2048
        with(Fixtures) { iso.putU32le(pvd + 156 + 10, 1_000_000_000L) }
        val read = countRead("iso", iso, 4_700_000_000L, SystemId.PS2)
        assertTrue(read <= budget, "read $read")
        assertEquals(true, read > 0)
    }
}
