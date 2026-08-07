package yux.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {

    /** 捕获 stdout/stderr 并执行 [runCli]（不经过 System.exit，避免杀测试 JVM）。 */
    private fun capture(vararg args: String): Triple<String, String, Int> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        try {
            val code = runCli(arrayOf(*args))
            return Triple(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), code)
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
    }

    @Test
    fun `ast command dumps program tree`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  print \"Hello Yux\"\n}")
        val (out, _, code) = capture("ast", file.toString())
        assertEquals(0, code)
        assertTrue(out.contains("program"), out)
        assertTrue(out.contains("fun main()"), out)
        assertTrue(out.contains("CallNP(Id(print)"), out)
    }

    @Test
    fun `lex command emits tokens`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "x = 1")
        val (out, _, code) = capture("lex", file.toString())
        assertEquals(0, code)
        assertTrue(out.contains("IDENTIFIER"), out)
        assertTrue(out.contains("INT_LITERAL"), out)
    }

    @Test
    fun `missing file reports error to stderr and non-zero exit`() {
        val (out, err, code) = capture("lex", "/nonexistent/file.yux")
        assertEquals(1, code)
        assertTrue(out.isEmpty())
        assertTrue(err.contains("文件不存在"), "Expected error message in stderr: $err")
    }

    @Test
    fun `unknown command prints usage`() {
        val (_, err, code) = capture("bogus")
        assertEquals(1, code)
        assertTrue(err.contains("yuxc"), err)
    }
}
