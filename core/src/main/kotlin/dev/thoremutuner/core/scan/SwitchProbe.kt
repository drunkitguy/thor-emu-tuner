package dev.thoremutuner.core.scan

/**
 * Switch title ids come from the filename in v1 (`[0100ABCD12340000]`). NSP ticket parsing is
 * planned after v0.1. Update ids (low 12 bits == 0x800) are normalized to the base application id;
 * DLC ids are ignored (PLAN section 6.3, Eden submission_package.cpp masks).
 */
object SwitchProbe {
    private val TID = Regex("\\[(01[0-9A-Fa-f]{14})]")

    fun titleIdFromFileName(name: String): String? {
        val raw = TID.find(name)?.groupValues?.get(1) ?: return null
        return normalizeTitleId(raw)
    }

    /** Base application id, or null for DLC ids. */
    fun normalizeTitleId(hex: String): String? {
        val v = hex.toULongOrNull(16) ?: return null
        val low = (v and 0xFFFUL).toInt()
        val base = when (low) {
            0 -> v
            0x800 -> v and 0xFFFFFFFFFFFFF000UL
            else -> return null // add-on content
        }
        return base.toString(16).uppercase().padStart(16, '0')
    }
}
