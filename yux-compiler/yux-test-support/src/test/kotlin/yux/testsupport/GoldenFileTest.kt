package yux.testsupport

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenFileTest {

    @Test
    fun `assertMatches passes when actual equals committed golden`() {
        GoldenFile().assertMatches("sample.upper.txt", "hello\nyux".uppercase())
    }

    @Test
    fun `assertMatches fails with clear message on mismatch`() {
        val dir = createTempDirectory("golden-mismatch")
        Path.of(dir.toString(), "a.txt").writeText("expected\n")
        val error = assertFailsWith<AssertionError> {
            GoldenFile(dir).assertMatches("a.txt", "actual\n")
        }
        assertTrue(error.message.orEmpty().contains("Golden mismatch"))
        assertTrue(error.message.orEmpty().contains("expected"))
    }

    @Test
    fun `assertMatches reports missing golden file`() {
        val dir = createTempDirectory("golden-missing")
        val error = assertFailsWith<AssertionError> {
            GoldenFile(dir).assertMatches("missing.txt", "x")
        }
        assertTrue(error.message.orEmpty().contains("Missing golden file"))
    }

    @Test
    fun `update mode writes golden file`() {
        val dir = createTempDirectory("golden-update")
        GoldenFile(dir, update = true).assertMatches("b.txt", "content")
        assertEquals("content\n", Path.of(dir.toString(), "b.txt").readText())
    }

    @Test
    fun `update mode truncates trailing newlines for stable golden files`() {
        val dir = createTempDirectory("golden-update2")
        GoldenFile(dir, update = true).assertMatches("c.txt", "line1\n\n")
        assertEquals("line1\n", Path.of(dir.toString(), "c.txt").readText())
    }
}
