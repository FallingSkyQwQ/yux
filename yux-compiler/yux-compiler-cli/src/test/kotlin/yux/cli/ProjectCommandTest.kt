package yux.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M7 项目命令测试（T-M7-5，02-§14）：`yuxc build/run -p/test` 接线验证。
 *
 * `run -p` 为进程内编译，不经过 Gradle；`build/test` 需要托管 Gradle，
 * 不可用时按 assumption 跳过（不失败）。
 */
class ProjectCommandTest {

    /** 捕获 stdout/stderr 并执行 [runCli]（与 MainTest 相同模式）。 */
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

    /** 构造临时 Yux 项目（build.yml + src/main.yux），返回项目根目录。 */
    private fun tempProject(): Path {
        val dir = Files.createTempDirectory("yux-project")
        Files.writeString(
            dir.resolve("build.yml"),
            """
            name: demo
            version: 1.0.0
            language:
              yux: "1.0"
            target:
              jvm: 21
            source:
              main: src
            """.trimIndent(),
        )
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/main.yux"), "fun main() {\n  print \"Hello Yux build\"\n}")
        return dir
    }

    /**
     * Gradle 可用性探测：`locateGradle` 解析到存在的路径，或 `gradle --version` 可执行。
     * build/test 需要嵌套 Gradle，不可用时跳过对应用例。
     */
    private fun gradleAvailable(projectDir: Path): Boolean {
        val located = yux.buildtool.GradleRunner.locateGradle(projectDir)
        if (located != "gradle") {
            val resolved = Path.of(located)
            if (Files.exists(resolved)) return true
        }
        return try {
            val proc = ProcessBuilder("gradle", "--version").redirectErrorStream(true).start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun `run project mode compiles and executes main`() {
        val project = tempProject()
        val (out, err, code) = capture("run", "-p", project.toString())
        assertEquals(0, code, err)
        assertTrue(out.contains("Hello Yux build"), out)
    }

    @Test
    fun `build project produces jar`() {
        val project = tempProject()
        assumeTrue(gradleAvailable(project), "Gradle 不可用，跳过")
        val (out, err, code) = capture("build", "-p", project.toString(), "--offline")
        assertEquals(0, code, err)
        assertTrue(out.contains("构建成功"), out)
        val jar = project.resolve("build/libs/demo-1.0.0.jar")
        assertTrue(Files.exists(jar), "jar 产物应存在: $jar")
    }

    @Test
    fun `build project without build yml reports error`() {
        val project = Files.createTempDirectory("yux-nobuild")
        val (_, err, code) = capture("build", "-p", project.toString())
        assertEquals(1, code)
        assertTrue(err.contains("未找到 build.yml"), err)
    }

    @Test
    fun `build nonexistent project reports error`() {
        val missing = Path.of("/nonexistent/yux-project")
        val (_, err, code) = capture("build", "-p", missing.toString())
        assertEquals(1, code)
        assertTrue(err.contains("项目目录不存在"), err)
    }

    @Test
    fun `build accepts clean flag`() {
        val project = tempProject()
        assumeTrue(gradleAvailable(project), "Gradle 不可用，跳过")
        val (out, err, code) = capture("build", "--clean", "-p", project.toString(), "--offline")
        assertEquals(0, code, err)
        assertTrue(out.contains("构建成功"), out)
    }

    @Test
    fun `test command reports pass on successful build`() {
        val project = tempProject()
        assumeTrue(gradleAvailable(project), "Gradle 不可用，跳过")
        val (out, err, code) = capture("test", "-p", project.toString(), "--offline")
        assertEquals(0, code, err)
        assertTrue(out.contains("测试通过"), out)
    }
}
