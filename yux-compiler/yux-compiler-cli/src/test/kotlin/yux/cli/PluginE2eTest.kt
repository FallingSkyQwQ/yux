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
 * M6 插件 e2e（T-M6-3/T-M6-6）：`--plugin <jar>` 加载 hello-extension，
 * 验证「插件扩展关键字 → 解析 → 下沉 → 字节码」链路（06-§M6 验收命令）。
 */
class PluginE2eTest {

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

    /** 定位 samples/extension 插件 jar（由 :samples:extension:jar 构建）。 */
    private fun extensionJar(): java.nio.file.Path? {
        val dirs = listOf(
            java.nio.file.Path.of("samples/extension/build/libs").toAbsolutePath().normalize(),
            java.nio.file.Path.of("../samples/extension/build/libs").toAbsolutePath().normalize(),
        )
        for (dir in dirs) {
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { stream ->
                    val jar = stream.filter { it.toString().endsWith(".jar") }.findFirst()
                    if (jar.isPresent) return jar.get()
                }
            }
        }
        return null
    }

    @Test
    fun `run with plugin jar executes greet extension`() {
        val jar = extensionJar() ?: return
        val file = Files.createTempFile("greet", ".yux")
        Files.writeString(file, "greet \"Hello, Yux plugin!\"\n")
        val (out, err, code) = capture("run", "--plugin", jar.toString(), file.toString())
        assertEquals(0, code, err)
        assertEquals("Hello, Yux plugin!", out)
    }

    @Test
    fun `run with plugin option after file path`() {
        val jar = extensionJar() ?: return
        val file = Files.createTempFile("greet2", ".yux")
        Files.writeString(file, "greet \"World\"\n")
        val (out, err, code) = capture("run", file.toString(), "--plugin", jar.toString())
        assertEquals(0, code, err)
        assertEquals("Hello, World!", out)
    }

    @Test
    fun `ir with plugin shows sunk plain AST`() {
        val jar = extensionJar() ?: return
        val file = Files.createTempFile("greet3", ".yux")
        Files.writeString(file, "greet \"X\"\n")
        val (out, err, code) = capture("ir", "--plugin", jar.toString(), file.toString())
        assertEquals(0, code, err)
        assertTrue(out.contains("main"), out)
        // 扩展节点不得泄漏到 IR
        assertFalse(out.contains("greet"), "扩展关键字不得泄漏到 IR: $out")
    }

    @Test
    fun `ast with plugin shows sunk main function`() {
        val jar = extensionJar() ?: return
        val file = Files.createTempFile("greet4", ".yux")
        Files.writeString(file, "greet \"Y\"\n")
        val (out, err, code) = capture("ast", "--plugin", jar.toString(), file.toString())
        assertEquals(0, code, err)
        assertTrue(out.contains("fun main()"), out)
    }

    @Test
    fun `missing plugin jar reports error`() {
        val (_, err, code) = capture("run", "--plugin", "/nonexistent/plugin.jar", "x.yux")
        assertEquals(1, code)
        assertTrue(err.contains("插件加载失败") || err.contains("文件不存在"), err)
    }
}
