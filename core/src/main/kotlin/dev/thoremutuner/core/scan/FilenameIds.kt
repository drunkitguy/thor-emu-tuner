package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId

/** Filename fallbacks (PLAN section 6.5). Method = FILENAME. */
object FilenameIds {
    val GC_WII: List<Regex> = listOf(Regex("\\[([A-Z0-9]{6})]"), Regex("\\(([A-Z0-9]{6})\\)"))
    val PS_SERIAL = Regex("\\b(S[CL][UEPAKC][SMD]|SL[EPU]S)[-_ ]?(\\d{3})\\.?(\\d{2})\\b")
    val PSP = Regex("\\b([UN][CLP][UEJAKS][SMBHDFG])[-_]?(\\d{5})\\b")
    val N3DS = Regex("\\b(000400[0-9A-Fa-f]{10})\\b")
    val VITA = Regex("\\b(PCS[A-H]\\d{5})\\b")

    fun gcWii(name: String): String? = GC_WII.firstNotNullOfOrNull { it.find(name)?.groupValues?.get(1) }

    fun psSerial(name: String): String? = PS_SERIAL.find(name)?.let { m ->
        "${m.groupValues[1]}-${m.groupValues[2]}${m.groupValues[3]}"
    }

    fun psp(name: String): String? = PSP.find(name)?.let { m -> m.groupValues[1] + m.groupValues[2] }

    fun n3ds(name: String): String? = N3DS.find(name)?.groupValues?.get(1)?.uppercase()

    fun vita(name: String): String? = VITA.find(name)?.groupValues?.get(1)

    fun switch(name: String): String? = SwitchProbe.titleIdFromFileName(name)

    /** The filename-derived id for [system], or null. */
    fun detect(system: SystemId, fileName: String): DetectedId? {
        val (value, kind) = when (system) {
            SystemId.GC, SystemId.WII -> gcWii(fileName) to IdKind.GC_WII_ID6
            SystemId.PS2 -> psSerial(fileName) to IdKind.PS2_SERIAL
            SystemId.PSX -> psSerial(fileName) to IdKind.PSX_SERIAL
            SystemId.PSP -> psp(fileName) to IdKind.PSP_DISC_ID
            SystemId.N3DS -> n3ds(fileName) to IdKind.N3DS_TITLE_ID
            SystemId.SWITCH -> switch(fileName) to IdKind.SWITCH_TITLE_ID
            SystemId.PSVITA -> vita(fileName) to IdKind.VITA_TITLE_ID
            else -> null to IdKind.NONE
        }
        return value?.let { DetectedId(it, kind, DetectionMethod.FILENAME) }
    }
}
