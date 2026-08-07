package yux.testsupport

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * golden 快照工具（06-§7.1）。
 *
 * golden 文件存放于 [rootDir]，默认 `src/test/resources/golden/`（Gradle 以模块目录为
 * 测试工作目录，因此相对路径可解析）。设置环境变量 `UPDATE_GOLDEN=1` 时覆盖快照，
 * 供 CI 逃生阀使用。
 */
class GoldenFile(
    private val rootDir: Path = DEFAULT_ROOT,
    private val update: Boolean = isUpdateMode(),
) {
    companion object {
        val DEFAULT_ROOT: Path = Path.of("src/test/resources/golden")
        const val UPDATE_ENV: String = "UPDATE_GOLDEN"

        fun isUpdateMode(): Boolean = System.getenv(UPDATE_ENV) == "1"
    }

    /** 断言 [actual] 与名为 [name] 的 golden 快照一致；update 模式下覆盖写回。 */
    fun assertMatches(name: String, actual: String) {
        val file = rootDir.resolve(name)
        if (update) {
            write(file, actual)
            return
        }
        if (!Files.exists(file)) {
            throw AssertionError(
                "Missing golden file: $file\nActual content was:\n$actual",
            )
        }
        val expected = file.readText().trimEnd('\n')
        val actualTrimmed = actual.trimEnd('\n')
        if (expected != actualTrimmed) {
            throw AssertionError("Golden mismatch: $file\n${diff(expected, actualTrimmed)}")
        }
    }

    private fun write(file: Path, content: String) {
        file.parent?.createDirectories()
        file.writeText(content.trimEnd('\n') + "\n")
    }

    private fun diff(expected: String, actual: String): String {
        val expectedLines = expected.split('\n')
        val actualLines = actual.split('\n')
        return buildString {
            append("=== expected (${expectedLines.size} lines) ===")
            expectedLines.forEachIndexed { i, line ->
                append('\n').append(i + 1).append(": ").append(line)
            }
            append("\n=== actual (${actualLines.size} lines) ===")
            actualLines.forEachIndexed { i, line ->
                append('\n').append(i + 1).append(": ").append(line)
            }
        }
    }
}
