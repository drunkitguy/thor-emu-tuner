package dev.thoremutuner.core.text

/**
 * Splits long read-only text (About, sources list, licenses, config previews) into small blocks so
 * each can be a separate D-pad focus stop. A focused block is brought into view top-aligned, so a
 * block taller than the viewport would hide its tail; blocks are therefore capped by length:
 *
 * - paragraphs (separated by blank lines) never share a block;
 * - every Markdown table row (`| ... |`) is its own block;
 * - other lines are packed into a block while it stays within [maxChars];
 * - a line longer than [maxChars] is split at word boundaries (a single word longer than the limit,
 *   such as a very long URL, is hard-split).
 *
 * Whitespace inside a line is preserved except where a line is split at a word boundary.
 */
object TextBlocks {
    const val DEFAULT_MAX_CHARS = 200

    fun split(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 20) { "maxChars too small" }
        val out = mutableListOf<String>()
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
        for (paragraph in paragraphs) {
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) out += current.toString().trimEnd()
                current.setLength(0)
            }
            for (rawLine in paragraph.split('\n')) {
                val line = rawLine.trimEnd()
                if (line.isBlank()) continue
                val isTableRow = line.trimStart().startsWith("|")
                if (isTableRow || line.length > maxChars) {
                    flush()
                    out += wrap(line, maxChars)
                    continue
                }
                val extra = if (current.isEmpty()) line.length else line.length + 1
                if (current.length + extra > maxChars) flush()
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
            flush()
        }
        return out
    }

    /** Splits one line into pieces of at most [maxChars], preferring word boundaries. */
    fun wrap(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val pieces = mutableListOf<String>()
        var rest = line.trim()
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars // one very long word: hard split
            pieces += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
