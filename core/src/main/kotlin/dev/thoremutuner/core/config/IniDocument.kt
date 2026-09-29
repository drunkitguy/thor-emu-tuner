package dev.thoremutuner.core.config

/**
 * Order-preserving INI model (PLAN section 7.1). Comments (`#`, `;`), blank lines, unknown sections
 * and unknown keys are kept verbatim; only lines touched by [set]/[remove] are rewritten. Keys are
 * case-sensitive and may contain `\` and `.`. The empty section name `""` addresses lines before the
 * first section header (RetroArch style). Output uses `\n` line endings, except that a file which
 * consistently used CRLF keeps CRLF (so untouched files round-trip byte for byte).
 */
class IniDocument private constructor(
    private val lines: MutableList<Line>,
    private var trailingNewline: Boolean,
    /** "\n" for new files; a file that consistently used CRLF keeps CRLF so round-trips are exact. */
    private val eol: String = "\n",
) {
    private sealed class Line {
        abstract val raw: String
        data class Other(override val raw: String) : Line()
        data class Header(val name: String, override val raw: String) : Line()
        data class Entry(val key: String, val value: String, override val raw: String) : Line()
    }

    /** Section name of each line (headers belong to their own section). */
    private fun sectionOf(index: Int): String {
        for (i in index downTo 0) {
            val l = lines[i]
            if (l is Line.Header) return l.name
        }
        return ""
    }

    fun sections(): List<String> {
        val out = mutableListOf<String>()
        if (lines.takeWhile { it !is Line.Header }.any { it is Line.Entry }) out += ""
        lines.filterIsInstance<Line.Header>().forEach { if (it.name !in out) out += it.name }
        return out
    }

    fun hasSection(section: String): Boolean =
        if (section.isEmpty()) true else lines.any { it is Line.Header && it.name == section }

    /** Value of the last matching entry, or null. */
    fun get(section: String, key: String): String? {
        var result: String? = null
        var current = ""
        for (l in lines) {
            when (l) {
                is Line.Header -> current = l.name
                is Line.Entry -> if (current == section && l.key == key) result = l.value
                is Line.Other -> Unit
            }
        }
        return result
    }

    /** All (key, value) entries of a section, in file order. */
    fun entries(section: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var current = ""
        for (l in lines) {
            when (l) {
                is Line.Header -> current = l.name
                is Line.Entry -> if (current == section) out += l.key to l.value
                is Line.Other -> Unit
            }
        }
        return out
    }

    /**
     * Sets a value. Existing entries are rewritten in place as `key<separator>value`; a missing key
     * goes after the last entry of its section (or right after the header); a missing section is
     * appended at the end.
     */
    fun set(section: String, key: String, value: String, separator: String = " = ") {
        require('\n' !in value && '\r' !in value) { "value must be a single line" }
        require('\n' !in key && '\r' !in key && key.isNotBlank()) { "invalid key" }
        val newLine = Line.Entry(key, value, "$key$separator$value")
        var found = false
        for (i in lines.indices) {
            val l = lines[i]
            if (l is Line.Entry && l.key == key && sectionOf(i) == section) {
                if (l.value != value || l.raw != newLine.raw) lines[i] = newLine
                found = true
            }
        }
        if (found) return
        val insertAt = insertionIndex(section)
        if (insertAt != null) {
            lines.add(insertAt, newLine)
            return
        }
        // Section missing: append it.
        if (lines.isNotEmpty() && lines.last().raw.isNotBlank()) lines += Line.Other("")
        lines += Line.Header(section, "[$section]")
        lines += newLine
    }

    /** Removes every entry of [key] in [section]. Returns true if something was removed. */
    fun remove(section: String, key: String): Boolean {
        var removed = false
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l is Line.Entry && l.key == key && sectionOf(i) == section) {
                lines.removeAt(i); removed = true
            } else {
                i++
            }
        }
        return removed
    }

    private fun insertionIndex(section: String): Int? {
        if (section.isEmpty()) {
            val firstHeader = lines.indexOfFirst { it is Line.Header }.let { if (it < 0) lines.size else it }
            val lastEntry = (0 until firstHeader).lastOrNull { lines[it] is Line.Entry }
            return if (lastEntry != null) lastEntry + 1 else 0
        }
        val header = lines.indexOfFirst { it is Line.Header && it.name == section }
        if (header < 0) return null
        val end = (header + 1 until lines.size).firstOrNull { lines[it] is Line.Header } ?: lines.size
        val lastEntry = (header + 1 until end).lastOrNull { lines[it] is Line.Entry }
        if (lastEntry != null) return lastEntry + 1
        // No entries yet: go after the section's leading comments, before trailing blank lines.
        var i = end - 1
        while (i > header && lines[i].raw.isBlank()) i--
        return i + 1
    }

    fun serialize(): String {
        if (lines.isEmpty()) return ""
        return lines.joinToString(eol) { it.raw } + if (trailingNewline) eol else ""
    }

    override fun toString(): String = serialize()

    companion object {
        fun parse(text: String?): IniDocument {
            if (text.isNullOrEmpty()) return IniDocument(mutableListOf(), true)
            val lf = text.count { it == '\n' }
            val crlf = Regex("\r\n").findAll(text).count()
            val eol = if (lf > 0 && crlf == lf) "\r\n" else "\n"
            val normalized = text.replace("\r\n", "\n")
            val trailing = normalized.endsWith("\n")
            val body = if (trailing) normalized.dropLast(1) else normalized
            val lines = body.split("\n").mapTo(mutableListOf()) { parseLine(it) }
            return IniDocument(lines, trailing, eol)
        }

        fun empty(): IniDocument = IniDocument(mutableListOf(), true)

        private fun parseLine(raw: String): Line {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) return Line.Other(raw)
            if (t.startsWith("[") && t.endsWith("]")) return Line.Header(t.substring(1, t.length - 1).trim(), raw)
            val eq = raw.indexOf('=')
            if (eq > 0) {
                val key = raw.substring(0, eq).trim()
                if (key.isNotEmpty()) return Line.Entry(key, raw.substring(eq + 1).trim(), raw)
            }
            return Line.Other(raw)
        }
    }
}
