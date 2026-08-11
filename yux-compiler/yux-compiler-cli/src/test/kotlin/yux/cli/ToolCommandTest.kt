package yux.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M16 新命令测试：new 脚手架 / fmt 格式化 / version / doc / help / 彩色诊断。
 */
class ToolCommandTest {

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

    // ── new（子进程：真实工作目录语义）───────────────────────────────────────

    /** 在 [dir] 工作目录下运行 yuxc（子进程，测试 JVM 的 classpath 完整可用）。 */
    private fun runIn(dir: java.nio.file.Path, vararg args: String): Triple<String, String, Int> {
        val javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java"
        val pb = ProcessBuilder(
            listOf(javaBin, "-cp", System.getProperty("java.class.path"), "yux.cli.MainKt") + args,
        ).directory(dir.toFile())
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val err = proc.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        return Triple(out, err, proc.waitFor())
    }

    @Test
    fun `new project template creates buildable project`() {
        val dir = Files.createTempDirectory("yux-new")
        val projectDir = dir.resolve("demo")
        val (out, err, code) = runIn(dir, "new", "demo")
        assertEquals(0, code, "$out$err")
        assertTrue(out.contains("已创建 project 项目"), out)
        assertTrue(Files.isRegularFile(projectDir.resolve("build.yml")), "应生成 build.yml")
        assertTrue(Files.isRegularFile(projectDir.resolve("src/main.yux")), "应生成 src/main.yux")
        assertTrue(Files.readString(projectDir.resolve("src/main.yux")).contains("Hello, demo!"), "模板应替换 <name>")
        // 生成的 hello 项目应可运行
        val (out2, err2, code2) = runIn(dir, "run", projectDir.resolve("src/main.yux").toString())
        assertEquals(0, code2, err2)
        assertEquals("Hello, demo!", out2)
    }

    @Test
    fun `new hello template with package`() {
        val dir = Files.createTempDirectory("yux-new")
        val projectDir = dir.resolve("greet")
        val (out, err, code) = runIn(dir, "new", "greet", "-t", "hello", "--package", "com.example")
        assertEquals(0, code, "$out$err")
        assertTrue(out.contains("包名: com.example"), out)
        val main = Files.readString(projectDir.resolve("main.yux"))
        assertTrue(main.contains("package com.example"), main)
    }

    @Test
    fun `new rejects existing dir without force`() {
        val dir = Files.createTempDirectory("yux-new")
        assertEquals(0, runIn(dir, "new", "demo").third)
        val (_, err, code) = runIn(dir, "new", "demo")
        assertEquals(1, code)
        assertTrue(err.contains("目录已存在"), err)
        val (_, _, code2) = runIn(dir, "new", "demo", "--force")
        assertEquals(0, code2)
    }

    @Test
    fun `new rejects unknown template and bad name`() {
        val dir = Files.createTempDirectory("yux-new")
        val (_, err, code) = runIn(dir, "new", "x", "-t", "nope")
        assertEquals(1, code)
        assertTrue(err.contains("未知模板"), err)
        val (_, err2, code2) = runIn(dir, "new", "a/b")
        assertEquals(1, code2)
        assertTrue(err2.contains("非法项目名"), err2)
    }

    // ── fmt ──────────────────────────────────────────────────────────────────

    @Test
    fun `fmt formats to stdout and is idempotent`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main(){print \"x\"}")
        val (out, err, code) = capture("fmt", file.toString())
        assertEquals(0, code, err)
        assertEquals("fun main() {\n    print \"x\"\n}\n", out)
    }

    @Test
    fun `fmt in-place rewrites file`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main(){print \"x\"}")
        val (_, err, code) = capture("fmt", "-i", file.toString())
        assertEquals(0, code, err)
        assertEquals("fun main() {\n    print \"x\"\n}\n", Files.readString(file))
    }

    @Test
    fun `fmt check passes and fails`() {
        val good = Files.createTempFile("yux", ".yux")
        Files.writeString(good, "fun main() {\n    print \"x\"\n}\n")
        val (out, _, code) = capture("fmt", "--check", good.toString())
        assertEquals(0, code)
        assertTrue(out.contains("全部已格式化"), out)

        val bad = Files.createTempFile("yux", ".yux")
        Files.writeString(bad, "fun main(){print \"x\"}")
        val (_, err, code2) = capture("fmt", "--check", bad.toString())
        assertEquals(1, code2)
        assertTrue(err.contains("需要格式化"), err)
    }

    @Test
    fun `fmt stdin`() {
        val prevIn = System.`in`
        try {
            System.setIn("fun main(){print \"x\"}".byteInputStream())
            val (out, err, code) = capture("fmt")
            assertEquals(0, code, err)
            assertEquals("fun main() {\n    print \"x\"\n}\n", out)
        } finally {
            System.setIn(prevIn)
        }
    }

    @Test
    fun `fmt reports parse errors`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() { x = }")
        val (_, err, code) = capture("fmt", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("语法错误"), err)
    }

    // ── version / doc / help ─────────────────────────────────────────────────

    @Test
    fun `version prints version`() {
        val (out, _, code) = capture("version")
        assertEquals(0, code)
        assertTrue(out.startsWith("yuxc "), out)
    }

    @Test
    fun `doc prints url`() {
        val (out, _, code) = capture("doc")
        assertEquals(0, code)
        assertTrue(out.startsWith("https://github.com/FallingSkyQwQ/yux"), out)
        val (out2, _, _) = capture("doc", "02")
        assertTrue(out2.contains("02-进阶.md"), out2)
    }

    @Test
    fun `help command and bare invocation`() {
        val (out, _, code) = capture()
        assertEquals(0, code)
        assertTrue(out.contains("Usage: yuxc"), out)
        assertTrue(out.contains("run"), "help 应列出子命令")
        assertTrue(out.contains("fmt"), "help 应列出 fmt")

        val (out2, _, code2) = capture("help", "fmt")
        assertEquals(0, code2)
        assertTrue(out2.contains("格式化 Yux 源码"), out2)

        val (_, err3, code3) = capture("help", "bogus")
        assertEquals(1, code3)
        assertTrue(err3.contains("未知命令"), err3)
    }

    @Test
    fun `unknown command prints usage to stderr`() {
        val (_, err, code) = capture("bogus")
        assertEquals(1, code)
        assertTrue(err.contains("yuxc"), err)
        assertTrue(err.contains("bogus"), err)
    }

    @Test
    fun `completion generates script`() {
        val (out, _, code) = capture("--completion", "bash")
        assertEquals(0, code)
        assertTrue(out.contains("_yuxc"), "bash 补全应包含函数名")
    }

    // ── 彩色诊断（--color always 强制 ANSI）──────────────────────────────────

    @Test
    fun `colored diagnostics with source snippet`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = \"str\"\n}")
        val (_, err, code) = capture("check", "--color", "always", file.toString())
        assertEquals(1, code)
        assertTrue(err.contains("\u001b[31m"), "ERROR 应为红色: ${err.take(100)}")
        assertTrue(err.contains("x:Int = \"str\""), "应含源码行标注: $err")
        assertTrue(err.contains("^\u001b"), "应含插入符: $err")
        // 排版：message 在源码标注之前，标注行带行号前缀
        assertTrue(err.indexOf("类型不匹配") < err.indexOf("x:Int = \"str\""), "message 应在源码行之前: $err")
        assertTrue(err.contains("2 |   x:Int"), "标注行应含行号: $err")
    }

    @Test
    fun `color auto is off when not tty`() {
        val file = Files.createTempFile("yux", ".yux")
        Files.writeString(file, "fun main() {\n  x:Int = \"str\"\n}")
        val (_, err, code) = capture("check", file.toString())
        assertEquals(1, code)
        assertFalse(err.contains("\u001b["), "非 tty 不应输出 ANSI: ${err.take(100)}")
    }
}
