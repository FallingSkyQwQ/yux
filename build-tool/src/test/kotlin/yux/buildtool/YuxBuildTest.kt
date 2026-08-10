package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * YuxBuild 编排器集成测试（T-M7-3/5/7）：解析 → 计划 → 增量 → Yux 编译 →
 * 生成 Gradle → 托管打包全链路。嵌套 Gradle 较慢，仅覆盖本里程碑验收场景。
 */
class YuxBuildTest {

    @TempDir
    lateinit var projectDir: Path

    /** repo 根 gradlew 绝对路径（显式传入，绕过环境变量定位）。 */
    private val repoGradlew: String = findRepoGradlew()

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

    /** 写入最小纯 Yux 项目：build.yml + src/main.yux。 */
    private fun writeProject(mainText: String) {
        projectDir.resolve("build.yml").writeText(
            """
            name: demo
            version: 1.0.0
            language:
              yux: "1.0"
            target:
              jvm: 21
            """.trimIndent() + "\n",
        )
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText(mainText)
    }

    private fun yuxBuild(): YuxBuild = YuxBuild(projectDir, gradleBinary = repoGradlew)

    @Test
    fun `完整构建产出 jar 类文件符号映射与缓存清单`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")

        val result = yuxBuild().build(offline = true)

        assertTrue(result.success, result.message)
        val jar = projectDir.resolve("build/libs/demo-1.0.0.jar")
        assertTrue(Files.isRegularFile(jar), "jar 应存在")
        assertEquals(jar, result.artifactJar)
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-classes/Main.class")))
        val symbols = projectDir.resolve("build/yux-symbols.json").readText()
        assertTrue(symbols.contains("\"class\": \"Main\""), symbols)
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-cache/manifest.json")))
    }

    @Test
    fun `无改动重复构建为增量命中并跳过 Gradle`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        val second = build.build(offline = true)

        assertTrue(second.success, second.message)
        assertFalse(second.compiled, "第二次构建应增量命中（compiled = false）")
        assertTrue(Files.isRegularFile(assertNotNull(second.artifactJar)))
    }

    @Test
    fun `修改源码后重新编译`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        projectDir.resolve("src/main.yux").writeText("fun main() { print \"Hello again\" }\n")
        val result = build.build(offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "源码变更后应重新编译")
    }

    @Test
    fun `修改构建配置后缓存失效并重新编译`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        // 改 mainClass（jar 名不变，仅影响生成的 Gradle 脚本与 plugin.yml 内容）
        projectDir.resolve("build.yml").writeText(
            """
            name: demo
            version: 1.0.0
            language:
              yux: "1.0"
            target:
              jvm: 21
            main: com.example.Main
            """.trimIndent() + "\n",
        )
        val result = build.build(offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "build.yml 变更后应重新编译（不再 upToDate）")
    }

    @Test
    fun `clean 构建强制重新编译并重建缓存`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        val result = build.build(clean = true, offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "clean 构建应重新编译")
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-cache/manifest.json")))
    }

    @Test
    fun `compile 返回进程内类字节与主类名`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")

        val compiled = yuxBuild().compile()

        assertTrue(compiled.success)
        assertEquals("Main", compiled.mainClassName)
        assertTrue(compiled.classes.containsKey("Main"))
        assertTrue(assertNotNull(compiled.classes["Main"]).isNotEmpty())
    }
}
