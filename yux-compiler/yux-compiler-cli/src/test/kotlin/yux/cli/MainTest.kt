package yux.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertTrue

class MainTest {

    private fun capture(vararg args: String): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        try {
            main(arrayOf(*args))
        } catch (e: Exception) {
            // exitProcess throws SecurityException in test environment
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
        return out.toString(StandardCharsets.UTF_8) to err.toString(StandardCharsets.UTF_8)
    }

    @Test
    fun `ast command dumps program tree`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  print \"Hello Yux\"\n}")
        val (out, err) = capture("ast", file.toString())
        assertTrue(out.contains("program"), out)
        assertTrue(out.contains("fun main()"), out)
        assertTrue(out.contains("CallNP(Id(print)"), out)
    }

    @Test
    fun `lex command emits tokens`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "x = 1")
        val (out, err) = capture("lex", file.toString())
        assertTrue(out.contains("IDENTIFIER"), out)
        assertTrue(out.contains("INT_LITERAL"), out)
    }

    @Test
    fun `lex command on missing file reports error to stderr`() {
        val (out, err) = capture("lex", "/nonexistent/file.yux")
        assertTrue(out.isEmpty())
        assertTrue(err.contains("文件不存在"), "Expected error message in stderr: $err")
    }
}
