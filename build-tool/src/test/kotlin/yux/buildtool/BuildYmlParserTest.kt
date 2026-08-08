package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-M7-1：build.yml 解析与 schema 校验（02-§11.2 / 05-§3）。 */
class BuildYmlParserTest {

    @TempDir
    lateinit var projectDir: Path

    private val parser = BuildYmlParser()

    /** 在临时项目目录写入 build.yml 并解析。 */
    private fun parse(text: String): BuildConfig {
        Files.writeString(projectDir.resolve("build.yml"), text)
        return parser.parse(projectDir)
    }

    /** 断言解析抛出 [BuildConfigException] 且消息含关键字。 */
    private fun assertFails(text: String, keyword: String): BuildConfigException =
        assertFailsWith<BuildConfigException> { parse(text) }
            .also { assertTrue(it.message!!.contains(keyword), "消息应为: ${it.message}") }

    @Test
    fun `全字段 build 配置解析为完整 BuildConfig`() {
        val config = parse(
            """
            name: MyServer
            version: "1.0.0"

            language:
              yux: "1.0"

            target:
              jvm: 21

            source:
              main: src
              kotlin: kotlin
              java: java

            resources: assets
            tests: test

            dependencies:
              minecraft:
                paper: "1.21"
              maven:
                - group: com.example
                  artifact: shared-api
                  version: "2.0"

            plugins:
              minecraft: true
              serialization: true
              injection: false

            main: com.example.MainKt
            """.trimIndent(),
        )
        assertEquals("MyServer", config.name)
        assertEquals("1.0.0", config.version)
        assertEquals("1.0", config.languageYux)
        assertEquals(21, config.targetJvm)
        assertEquals("src", config.sourceMain)
        assertEquals("kotlin", config.sourceKotlin)
        assertEquals("java", config.sourceJava)
        assertEquals("assets", config.resourcesDir)
        assertEquals("test", config.testsDir)
        assertEquals(listOf(MavenCoord("com.example", "shared-api", "2.0")), config.mavenDeps)
        assertEquals("1.21", config.paperVersion)
        assertEquals(setOf("minecraft", "serialization"), config.pluginsEnabled)
        assertEquals("com.example.MainKt", config.mainClass)
    }

    @Test
    fun `最简 build 配置应用全部缺省值`() {
        val config = parse(
            """
            name: demo
            version: "1.0"
            """.trimIndent(),
        )
        assertEquals("demo", config.name)
        assertEquals("1.0", config.version)
        assertEquals("1.0", config.languageYux)
        assertEquals(21, config.targetJvm)
        assertEquals("src", config.sourceMain)
        assertNull(config.sourceKotlin)
        assertNull(config.sourceJava)
        assertEquals("resources", config.resourcesDir)
        assertEquals("tests", config.testsDir)
        assertEquals(emptyList(), config.mavenDeps)
        assertNull(config.paperVersion)
        assertEquals(emptySet(), config.pluginsEnabled)
        assertNull(config.mainClass)
    }

    @Test
    fun `缺少 build 配置文件报错`() {
        val exception = assertFailsWith<BuildConfigException> { parser.parse(projectDir) }
        assertEquals(
            "未找到 build.yml: ${projectDir.resolve("build.yml").toAbsolutePath()}",
            exception.message,
        )
    }

    @Test
    fun `文档示例的未加引号 YAML 数字版本字段被接受`() {
        // 02-§11.2 / 05-§3 示例：yux/paper/version 未加引号，YAML 解析为 Number → 转字符串
        val config = parse(
            """
            name: MyServer
            version: 1.0
            language:
              yux: 1.0
            dependencies:
              minecraft:
                paper: 1.21
              maven:
                - group: com.example
                  artifact: shared-api
                  version: 2.0
            """.trimIndent(),
        )
        assertEquals("1.0", config.version)
        assertEquals("1.0", config.languageYux)
        assertEquals("1.21", config.paperVersion)
        assertEquals("2.0", config.mavenDeps.single().version)
    }

    @Test
    fun `顶层不是映射报错`() {
        assertFails("- just: a-list", "build.yml 顶层必须是映射")
    }

    @Test
    fun `缺少必填字段 name 报错`() {
        val exception = assertFails(
            """
            version: "1.0"
            """.trimIndent(),
            "name",
        )
        assertEquals("build.yml 缺少必填字段: name", exception.message)
    }

    @Test
    fun `缺少必填字段 version 报错`() {
        val exception = assertFails(
            """
            name: demo
            """.trimIndent(),
            "version",
        )
        assertEquals("build.yml 缺少必填字段: version", exception.message)
    }

    @Test
    fun `name 类型错误报错`() {
        assertFails(
            """
            name: 123
            version: "1.0"
            """.trimIndent(),
            "name",
        )
    }

    @Test
    fun `target jvm 为字符串报错`() {
        val exception = assertFails(
            """
            name: demo
            version: "1.0"
            target:
              jvm: "21"
            """.trimIndent(),
            "target.jvm 必须是整数",
        )
        assertEquals("target.jvm 必须是整数", exception.message)
    }

    @Test
    fun `未知顶层字段被忽略`() {
        val config = parse(
            """
            name: demo
            version: "1.0"
            mystery:
              - 1
              - 2
            """.trimIndent(),
        )
        assertEquals("demo", config.name)
        assertEquals("1.0", config.version)
    }

    @Test
    fun `maven 项缺少 version 报错并定位序号`() {
        val exception = assertFails(
            """
            name: demo
            version: "1.0"
            dependencies:
              maven:
                - group: com.example
                  artifact: shared-api
            """.trimIndent(),
            "第 1 项",
        )
        assertEquals("dependencies.maven 第 1 项缺少 group/artifact/version", exception.message)
    }

    @Test
    fun `maven 第二项缺失字段报错序号为 2`() {
        assertFails(
            """
            name: demo
            version: "1.0"
            dependencies:
              maven:
                - group: com.example
                  artifact: ok
                  version: "1.0"
                - group: org.other
                  artifact: bad
            """.trimIndent(),
            "第 2 项",
        )
    }

    @Test
    fun `plugins 仅收集值为 true 的键`() {
        val config = parse(
            """
            name: demo
            version: "1.0"
            plugins:
              minecraft: true
              serialization: false
              injection: enabled
            """.trimIndent(),
        )
        assertEquals(setOf("minecraft"), config.pluginsEnabled)
    }

    @Test
    fun `plugins 类型错误报错`() {
        assertFails(
            """
            name: demo
            version: "1.0"
            plugins: x
            """.trimIndent(),
            "plugins 必须是映射",
        )
    }
}
