package yux.io

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IOTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `写后读 round-trip 保持一致（UTF-8 中文）`() {
        val file = tempDir.resolve("hello.txt").toString()
        IO.writeText(file, "你好, Yux！Hello, 世界。")
        assertEquals("你好, Yux！Hello, 世界。", IO.readText(file))
    }

    @Test
    fun `readLines 按行拆分多行文本`() {
        val file = tempDir.resolve("lines.txt").toString()
        IO.writeText(file, "第一行\n第二行\r\n第三行\n")
        assertEquals(listOf("第一行", "第二行", "第三行"), IO.readLines(file))
    }

    @Test
    fun `readLines 空文件返回空列表`() {
        val file = tempDir.resolve("empty.txt").toString()
        IO.writeText(file, "")
        assertEquals(emptyList<String>(), IO.readLines(file))
    }

    @Test
    fun `readLines 返回 ArrayList 实现`() {
        val file = tempDir.resolve("list.txt").toString()
        IO.writeText(file, "a\nb\nc\n")
        val lines = IO.readLines(file)
        assertTrue(lines is ArrayList<*>)
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `exists 对已存在文件返回 true`() {
        val file = tempDir.resolve("exists.txt").toString()
        IO.writeText(file, "x")
        assertTrue(IO.exists(file))
    }

    @Test
    fun `exists 对不存在的路径返回 false`() {
        assertFalse(IO.exists(tempDir.resolve("missing.txt").toString()))
    }

    @Test
    fun `writeText 自动创建缺失的父目录`() {
        val file = tempDir.resolve("a/b/c/deep.txt").toString()
        IO.writeText(file, "deep")
        assertTrue(IO.exists(file))
        assertEquals("deep", IO.readText(file))
    }

    @Test
    fun `readText 缺失文件抛出含路径的 RuntimeException`() {
        val missing = tempDir.resolve("nope.txt").toString()
        val e = assertFailsWith<RuntimeException> { IO.readText(missing) }
        assertTrue(e.message!!.startsWith("yux.io"))
        assertTrue(e.message!!.contains(missing))
    }

    @Test
    fun `readText 异常保留原始 cause`() {
        val missing = tempDir.resolve("nope.txt").toString()
        val e = assertFailsWith<RuntimeException> { IO.readText(missing) }
        assertTrue(e.cause is java.nio.file.NoSuchFileException)
    }
}
