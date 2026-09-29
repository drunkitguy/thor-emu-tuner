package dev.thoremutuner.core.scan

/** PARAM.SFO (magic "\0PSF") and PBP (magic "\0PBP") parsing, as in PPSSPP ParamSFO.cpp / PBPReader.h. */
object SfoParser {
    private val SFO_MAGIC = byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private val PBP_MAGIC = byteArrayOf(0, 'P'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    private const val MAX_SFO = 64 * 1024

    fun isSfo(head: ByteArray): Boolean = head.startsWithAt(0, SFO_MAGIC)
    fun isPbp(head: ByteArray): Boolean = head.startsWithAt(0, PBP_MAGIC)

    /** Parses string and integer entries. Returns null if the data is not an SFO. */
    fun parse(data: ByteArray): Map<String, String>? {
        if (data.size < 20 || !isSfo(data)) return null
        val keyTable = data.u32le(8).toInt()
        val dataTable = data.u32le(12).toInt()
        val count = data.u32le(16).toInt()
        if (count !in 0..512) return null
        val out = linkedMapOf<String, String>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > data.size) break
            val keyOff = keyTable + data.u16le(e)
            val fmt = data.u16le(e + 2)
            val len = data.u32le(e + 4).toInt()
            val dataOff = dataTable + data.u32le(e + 12).toInt()
            if (keyOff !in data.indices || dataOff < 0 || dataOff > data.size) continue
            val key = data.cString(keyOff, 64)
            val value = when (fmt) {
                0x0404 -> if (dataOff + 4 <= data.size) data.u32le(dataOff).toString() else continue
                else -> data.cString(dataOff, minOf(len, data.size - dataOff))
            }
            out[key] = value
        }
        return out
    }

    /** Reads the PARAM.SFO embedded in a PBP (offsets u32[8] @8; SFO = [off0, off1)). */
    fun readPbpSfo(src: ByteSource): Map<String, String>? {
        val head = src.readExact(0, 40) ?: return null
        if (!isPbp(head)) return null
        val off0 = head.u32le(8)
        val off1 = head.u32le(12)
        val len = (off1 - off0).toInt()
        if (off0 < 40 || len <= 0 || len > MAX_SFO) return null
        return parse(src.readExact(off0, len) ?: return null)
    }
}
