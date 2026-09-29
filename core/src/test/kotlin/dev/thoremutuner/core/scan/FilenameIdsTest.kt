package dev.thoremutuner.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PLAN section 15, item 12: at least 3 positive and 2 negative cases per regex. */
class FilenameIdsTest {
    @Test fun gcWii() {
        assertEquals("GMSE01", FilenameIds.gcWii("Super Mario Sunshine [GMSE01].rvz"))
        assertEquals("RMGE01", FilenameIds.gcWii("Galaxy (RMGE01).wbfs"))
        assertEquals("G8ME01", FilenameIds.gcWii("Paper Mario (USA) [G8ME01].iso"))
        assertNull(FilenameIds.gcWii("Super Mario Sunshine (USA).rvz"))
        assertNull(FilenameIds.gcWii("Game (Europe) (En,Fr).iso"))
        assertNull(FilenameIds.gcWii("Game [gmse01].iso"))
    }

    @Test fun psSerial() {
        assertEquals("SLUS-20312", FilenameIds.psSerial("Game (USA) [SLUS-20312].iso"))
        assertEquals("SCUS-94163", FilenameIds.psSerial("SCUS_941.63.Final Fantasy.bin"))
        assertEquals("SLES-50330", FilenameIds.psSerial("SLES 50330 Game.chd"))
        assertEquals("SLPM-65432", FilenameIds.psSerial("[SLPM-65432] Game.iso"))
        assertNull(FilenameIds.psSerial("Game (USA).iso"))
        assertNull(FilenameIds.psSerial("SLUS-203 Game.iso"))
        assertNull(FilenameIds.psSerial("XLUS-20312.iso"))
    }

    @Test fun psp() {
        assertEquals("ULUS10041", FilenameIds.psp("Game [ULUS-10041].iso"))
        assertEquals("NPJH50465", FilenameIds.psp("NPJH50465 Game.cso"))
        assertEquals("UCES00001", FilenameIds.psp("Game (UCES_00001).iso"))
        assertNull(FilenameIds.psp("Game (USA).iso"))
        assertNull(FilenameIds.psp("ULUS-1004.iso"))
        assertNull(FilenameIds.psp("XLUS10041.iso"))
    }

    @Test fun n3ds() {
        assertEquals("0004000000055D00", FilenameIds.n3ds("Pokemon X (0004000000055D00).3ds"))
        assertEquals("000400000F700000", FilenameIds.n3ds("000400000f700000 Game.cia"))
        assertEquals("0004000000030800", FilenameIds.n3ds("Mario Kart 7 [0004000000030800].cci"))
        assertNull(FilenameIds.n3ds("Pokemon X (Europe).3ds"))
        assertNull(FilenameIds.n3ds("0005000000055D00.3ds"))
    }

    @Test fun switch() {
        assertEquals("01007EF00011E000", FilenameIds.switch("Zelda BOTW [01007EF00011E000][v0].nsp"))
        assertEquals("0100000000010000", FilenameIds.switch("Mario Odyssey [0100000000010000].xci"))
        assertEquals("01006F8002326000", FilenameIds.switch("Animal Crossing [01006F8002326800][v1].nsp"))
        assertNull(FilenameIds.switch("Zelda BOTW (USA).nsp"))
        assertNull(FilenameIds.switch("Game [02007EF00011E000].nsp"))
        assertNull(FilenameIds.switch("Game (01007EF00011E000).nsp"))
    }

    @Test fun vita() {
        assertEquals("PCSE00001", FilenameIds.vita("Game [PCSE00001].psvita"))
        assertEquals("PCSB00245", FilenameIds.vita("PCSB00245 Persona.psvita"))
        assertEquals("PCSH00021", FilenameIds.vita("Game (PCSH00021).vpk"))
        assertNull(FilenameIds.vita("Game (USA).psvita"))
        assertNull(FilenameIds.vita("PCSZ00001.psvita"))
    }
}
