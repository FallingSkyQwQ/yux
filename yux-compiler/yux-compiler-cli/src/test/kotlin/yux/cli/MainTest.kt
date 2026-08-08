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
    fun `check command runs semantic analysis`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = 1\n  print x\n}")
        val (out, err, code) = capture("check", file.toString())
        assertEquals(0, code, err)
        assertTrue(out.contains("IntLiteral : Int"), out)
        assertTrue(out.contains("Call : Unit"), out)
    }

    @Test
    fun `check command reports type error and exits non-zero`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = \"str\"\n}")
        val (_, err, code) = capture("check", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("E0004"), err)
    }

    @Test
    fun `ir command dumps module`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  print \"Hello Yux\"\n}")
        val (out, err, code) = capture("ir", file.toString())
        assertEquals(0, code, err)
        assertTrue(out.contains("module"), out)
        assertTrue(out.contains("(file)"), out)
        assertTrue(out.contains("yux.core.CoreLib.print"), out)
    }

    @Test
    fun `ir command reports errors and exits non-zero`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = \"str\"\n}")
        val (_, err, code) = capture("ir", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("E0004"), err)
    }

    @Test
    fun `unknown command prints usage`() {
        val (_, err, code) = capture("bogus")
        assertEquals(1, code)
        assertTrue(err.contains("yuxc"), err)
    }

    @Test
    fun `run command executes main`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  print \"Hello Yux\"\n}")
        val (out, err, code) = capture("run", file.toString())
        assertEquals(0, code, err)
        assertEquals("Hello Yux", out)
    }

    @Test
    fun `run command with data class toString`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(
            file,
            "data User {\n  id:Int\n  name:String\n}\nfun main() {\n  u = User(1, \"Steve\")\n  print u.toString\n}",
        )
        val (out, err, code) = capture("run", file.toString())
        assertEquals(0, code, err)
        assertEquals("User(id=1, name=Steve)", out)
    }

    @Test
    fun `run command reports compile errors`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = \"str\"\n}")
        val (_, err, code) = capture("run", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("E0004"), err)
    }

    @Test
    fun `run command reports runtime exceptions`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x = 1 / 0\n}")
        val (_, err, code) = capture("run", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("运行时异常"), err)
    }

    @Test
    fun `run command missing file`() {
        val (_, err, code) = capture("run", "/nonexistent/run.yux")
        assertEquals(1, code)
        assertTrue(err.contains("文件不存在"), err)
    }

    @Test
    fun `samples directory e2e compares stdout`() {
        // T-M5-11 端到端：samples/*.yux 逐一运行并比对 .stdout 快照
        val samplesDir = java.nio.file.Path.of("samples").toAbsolutePath().normalize()
        if (!java.nio.file.Files.isDirectory(samplesDir)) return
        java.nio.file.Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".yux") }.sorted().forEach { file ->
                val expected = java.nio.file.Files.readString(file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + ".stdout"))
                val (out, err, code) = capture("run", file.toString())
                assertEquals(0, code, "${file.fileName} 应运行成功: $err")
                assertEquals(expected, out, "${file.fileName} stdout 不匹配")
            }
        }
    }
}
