package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GradleRunner 托管调用集成测试（T-M7-4）：定位 gradle 可执行文件 + 嵌套 Gradle
 * 运行最小 `jar` 构建。测试工作目录 = build-tool 模块，repo 根 = `user.dir/../..`。
 */
class GradleRunnerTest {

    @TempDir
    lateinit var workDir: Path

    /** repo 根 gradlew 绝对路径（显式传入，绕过环境变量定位）。 */
    private val repoGradlew: String = findRepoGradlew()

    @Test
    fun `定位 gradle 可执行文件并运行最小项目 jar 构建`() {
        val repoRoot = Path.of(repoGradlew).parent
        val located = GradleRunner.locateGradle(repoRoot)
        assertTrue(located.isNotBlank(), "locateGradle 应返回非空")
        assertTrue(located == "gradle" || Files.exists(Path.of(located)), "定位的 gradle 应存在: $located")

        workDir.resolve("settings.gradle").writeText("rootProject.name = \"mini\"\n")
        workDir.resolve("build.gradle.kts").writeText("plugins { java }\n")

        val result = GradleRunner(gradleBinary = repoGradlew, offline = true).run(workDir, "jar")

        assertEquals(0, result.exitCode, result.output.takeLast(2000))
        val libs = workDir.resolve("build/libs")
        assertTrue(Files.isDirectory(libs), "应产出 build/libs 目录")
        val jarFound = Files.list(libs).use { stream ->
            stream.anyMatch { it.fileName.toString().endsWith(".jar") }
        }
        assertTrue(jarFound, "build/libs 下应有 jar")
    }

    /** 从测试工作目录向上找仓库根 gradlew（与测试工作目录层级解耦）。 */
    private fun findRepoGradlew(): String {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("gradlew")
            if (Files.isRegularFile(candidate)) return candidate.toString()
            dir = dir.parent
        }
        throw IllegalStateException("未找到仓库根 gradlew")
    }
}
