package dev.kotrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the code in the README and the guides honest (ADR-007/030): every ```kotlin block in [documents] is tagged
 * `<!-- snippet: name -->`, and every non-Gradle one must equal (imports aside, indentation normalized) a
 * `README snippet` region in a sample source file that `check` compiles. Editing a snippet without its compiled twin
 * (or vice versa) fails here, so no document can show code that no longer compiles.
 */
class ReadmeSnippetsTest {

    private val documents = listOf("README.md", "docs/writing-adapters.md")

    private val sources = listOf(
        "src/test/kotlin/dev/kotrace/samples/ReadmeSnippets.kt",
        "kotrace-okhttp/src/test/kotlin/dev/kotrace/okhttp/ReadmeSnippets.kt",
        "kotrace-room/src/test/kotlin/dev/kotrace/room/ReadmeSnippets.kt",
    )

    @Test fun `every documented kotlin block is tagged, and every tagged snippet matches its compiled twin`() {
        val regions = sources.flatMap { regionsOf(File(it).readLines()).toList() }.toMap()
        val used = mutableSetOf<String>()

        documents.forEach { document ->
            val lines = File(document).readLines()
            val blocks = snippetBlocks(lines)
            val untagged = lines.indices.filter { i ->
                lines[i].trim() == "```kotlin" && blocks.none { it.fenceLine == i }
            }
            assertTrue("$document kotlin blocks without a snippet tag at lines ${untagged.map { it + 1 }}", untagged.isEmpty())

            blocks.filter { it.name != "gradle" }.forEach { block ->
                val compiled = regions[block.name]
                    ?: throw AssertionError("$document snippet '${block.name}' has no compiled region in $sources")
                assertEquals("$document snippet '${block.name}' drifted from its compiled twin", normalize(compiled), normalize(block.lines))
                used += block.name
            }
        }
        val orphans = regions.keys - used
        assertTrue("compiled snippet regions no document uses: $orphans", orphans.isEmpty())
    }

    private class Block(val name: String, val fenceLine: Int, val lines: List<String>)

    private fun snippetBlocks(document: List<String>): List<Block> {
        val tag = Regex("""^\s*<!-- snippet: (\S+) -->$""")
        return document.indices.mapNotNull { i ->
            val name = tag.find(document[i])?.groupValues?.get(1) ?: return@mapNotNull null
            val fence = i + 1
            check(document[fence].trim() == "```kotlin") { "snippet tag at line ${i + 1} is not followed by a kotlin fence" }
            val end = (fence + 1 until document.size).first { document[it].trim() == "```" }
            Block(name, fence, document.subList(fence + 1, end))
        }
    }

    private fun regionsOf(source: List<String>): Map<String, List<String>> {
        val start = Regex("""^\s*// README snippet: (\S+)$""")
        return source.indices.mapNotNull { i ->
            val name = start.find(source[i])?.groupValues?.get(1) ?: return@mapNotNull null
            val end = (i + 1 until source.size).first { source[it].trim() == "// end README snippet" }
            name to source.subList(i + 1, end)
        }.toMap()
    }

    /** Drops imports and surrounding blank lines, strips the common indent and trailing spaces. */
    private fun normalize(lines: List<String>): String {
        val body = lines.filterNot { it.trimStart().startsWith("import ") }
            .dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        val indent = body.filter(String::isNotBlank).minOf { it.length - it.trimStart().length }
        return body.joinToString("\n") { if (it.isBlank()) "" else it.drop(indent).trimEnd() }
    }
}
