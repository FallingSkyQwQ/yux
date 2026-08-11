package yux.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * 圣经示例集 harness（T-M12，docs/tutorial 配套）。
 *
 * 扫描 `samples/tutorial/<篇>/NN-<name>.yux`：
 * - 有同名 `.stdout`  → 正例：`yuxc run` 必须成功，stdout 逐字节匹配快照；
 * - 有同名 `.err`     → 反例：`yuxc run` 必须失败（非零退出码），stderr 匹配快照。
 *
 * 与 MainTest.kt 的旧 samples e2e 不同，本 harness 从仓库根定位，
 * 不再因测试 CWD 偏移而静默跳过。
 */
class TutorialSamplesTest {

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

    /** 从测试 CWD 逐级向上找仓库根（含 samples/tutorial/ 的目录）。 */
    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath().normalize()
        while (dir != dir.parent) {
            if (Files.isDirectory(dir.resolve("samples").resolve("tutorial"))) return dir
            dir = dir.parent
        }
        throw AssertionError("未找到仓库根（samples/tutorial/ 不存在于任何父目录）")
    }

    @Test
    fun `tutorial samples e2e matches stdout and err snapshots`() {
        val root = repoRoot()
        val tutorialDir = root.resolve("samples").resolve("tutorial")
        var positive = 0
        var negative = 0
        Files.walk(tutorialDir).use { stream ->
            stream.filter { it.toString().endsWith(".yux") }.sorted().forEach { file ->
                val stem = file.fileName.toString().substringBeforeLast('.')
                val stdoutPath = file.resolveSibling("$stem.stdout")
                val errPath = file.resolveSibling("$stem.err")
                when {
                    Files.exists(stdoutPath) -> {
                        val expected = Files.readString(stdoutPath)
                        val (out, err, code) = capture("run", file.toString())
                        assertEquals(0, code, "${tutorialDir.relativize(file)} 正例应运行成功: $err")
                        assertEquals(expected, out, "${tutorialDir.relativize(file)} stdout 不匹配")
                        positive++
                    }
                    Files.exists(errPath) -> {
                        val expected = Files.readString(errPath)
                        val (out, err, code) = capture("run", file.toString())
                        assertEquals(1, code, "${tutorialDir.relativize(file)} 反例应编译失败: $err")
                        assertEquals(expected, err, "${tutorialDir.relativize(file)} stderr 不匹配")
                        negative++
                    }
                    else -> {
                        throw AssertionError("${tutorialDir.relativize(file)} 缺少 .stdout 或 .err 快照")
                    }
                }
            }
        }
        assert(positive > 0) { "tutorial 示例 harness 未发现任何正例" }
        assert(negative > 0) { "tutorial 示例 harness 未发现任何反例" }
    }
}
