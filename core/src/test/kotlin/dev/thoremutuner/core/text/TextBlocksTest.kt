package dev.thoremutuner.core.text

import dev.thoremutuner.core.TestFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBlocksTest {
    private val limit = TextBlocks.DEFAULT_MAX_CHARS

    /** All non-whitespace content survives, in order. */
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("")

    private fun check(name: String, text: String) {
        val blocks = TextBlocks.split(text)
        assertTrue(blocks.isNotEmpty(), name)
        val longest = blocks.maxOf { it.length }
        assertTrue(longest <= limit, "$name: a block has $longest chars (limit $limit)")
        assertEquals(words(text), words(blocks.joinToString(" ")), "$name: content lost or reordered")
    }

    @Test fun realSourcesListStaysWithinLimit() {
        val text = TestFiles.file("docs/SOURCES.md").readText()
        assertTrue(text.lines().any { it.length > limit }, "fixture should contain long table rows")
        check("SOURCES.md", text)
        check("assets/SOURCES.md", TestFiles.file("app/src/main/assets/SOURCES.md").readText())
    }

    @Test fun realApacheLicenseStaysWithinLimit() {
        val text = TestFiles.file("app/src/main/assets/licenses/Apache-2.0.txt").readText()
        assertTrue(text.lines().any { it.length > limit }, "fixture should contain long paragraphs")
        check("Apache-2.0.txt", text)
    }

    @Test fun mitLicenseAndGoldenConfigsStayWithinLimit() {
        check("LICENSE", TestFiles.file("LICENSE").readText())
        check("azahar golden", TestFiles.resource("golden/azahar_thor_balanced.ini"))
    }

    @Test fun tableRowsAreSeparateBlocks() {
        val md = "| a | b |\n|---|---|\n| row one | x |\n| row two | y |"
        assertEquals(listOf("| a | b |", "|---|---|", "| row one | x |", "| row two | y |"), TextBlocks.split(md))
    }

    @Test fun shortLinesArePackedAndParagraphsKeptApart() {
        val blocks = TextBlocks.split("[Core]\nkey = 1\nother = 2\n\n[Renderer]\nx = 3\n")
        assertEquals(listOf("[Core]\nkey = 1\nother = 2", "[Renderer]\nx = 3"), blocks)
    }

    @Test fun longWordsAreHardSplit() {
        val word = "x".repeat(450)
        val blocks = TextBlocks.split(word)
        assertEquals(listOf(200, 200, 50), blocks.map { it.length })
    }
}
