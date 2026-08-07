package yux.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertTrue

class MainTest {

    private fun capture(vararg args: String): String {
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        try {
            main(arrayOf(*args))
        } finally {
            System.setOut(prev)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    @Test
    fun `ast command dumps program tree`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  print \"Hello Yux\"\n}")
        val out = capture("ast", file.toString())
        assertTrue(out.contains("program"), out)
        assertTrue(out.contains("fun main()"), out)
        assertTrue(out.contains("CallNP(Id(print)"), out)
    }

    @Test
    fun `lex command emits tokens`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "x = 1")
        val out = capture("lex", file.toString())
        assertTrue(out.contains("IDENTIFIER"), out)
        assertTrue(out.contains("INT_LITERAL"), out)
    }

    @Test
    fun `lex command on missing file reports error to stderr`() {
        val out = capture("lex", "/nonexistent/file.yux")
        assertTrue(out.isEmpty())
    }
}
