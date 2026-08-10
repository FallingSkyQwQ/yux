package yux.buildtool

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import yux.testsupport.GoldenFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GradleGenerator 生成文本的 golden 快照测试（T-M7-2）。 */
class GradleGeneratorTest {

    private val generator = GradleGenerator()

    @Test
    fun `特殊字符在生成脚本中正确转义`() {
        val name = "a\"b\$c\\d"
        val mainClass = "com.example.\"Main\""
        val path = "C:\\yux\\build"
        val config = BuildConfig(
            name = name,
            version = "1.0.0",
            mainClass = mainClass,
            sourceJava = path,
            sourceKotlin = path,
            resourcesDir = path,
            mavenDeps = listOf(MavenCoord("com.example", "api", "2.0\$beta")),
            pluginsEnabled = setOf("minecraft"),
            paperVersion = "1.21",
        )
        val project = generator.generate(config, yuxClassesDir = path)

        val escapedName = "a\\\"b\\\$c\\\\d"
        val escapedMain = "com.example.\\\"Main\\\""
        val escapedPath = "C:\\\\yux\\\\build"
        val escapedDep = "com.example:api:2.0\\\$beta"
        assertTrue(project.settingsGradle.contains("""rootProject.name = "$escapedName""""), project.settingsGradle)
        assertTrue(project.buildGradleKts.contains("""mainClass.set("$escapedMain")"""), project.buildGradleKts)
        assertTrue(project.buildGradleKts.contains("""implementation(files("$escapedPath"))"""), project.buildGradleKts)
        assertTrue(project.buildGradleKts.contains("""implementation("$escapedDep")"""), project.buildGradleKts)
        assertTrue(project.buildGradleKts.contains("""srcDir("$escapedPath")"""), project.buildGradleKts)
        assertTrue(project.buildGradleKts.contains("""resources.srcDir("$escapedPath")"""), project.buildGradleKts)
        assertTrue(project.buildGradleKts.contains("""from("$escapedPath")"""), project.buildGradleKts)
        // plugin.yml 可被 YAML 解析器读取且值完整还原
        val parsed = Yaml().load<Map<String, Any?>>(assertNotNull(project.pluginYml))
        assertEquals(name, parsed["name"])
        assertEquals("1.0.0", parsed["version"])
        assertEquals(mainClass, parsed["main"])
        assertEquals("1.21", parsed["api-version"])
    }

    @Test
    fun `含冒号井号与前导引号的值在 plugin yml 中可解析`() {
        val config = BuildConfig(
            name = "plugin: #1",
            version = "1.0",
            mainClass = "\"Quoted\"Main",
            pluginsEnabled = setOf("minecraft"),
            paperVersion = "1.21",
        )
        val project = generator.generate(config)

        val parsed = Yaml().load<Map<String, Any?>>(assertNotNull(project.pluginYml))
        assertEquals("plugin: #1", parsed["name"])
        assertEquals("1.0", parsed["version"])
        assertEquals("\"Quoted\"Main", parsed["main"])
    }

    @Test
    fun `pure yux project generates settings build and no plugin yml`() {
        val project = generator.generate(BuildConfig(name = "helloworld", version = "0.1.0"))

        GoldenFile().assertMatches("gradle.settings.pure.txt", project.settingsGradle)
        GoldenFile().assertMatches("gradle.build.pure.txt", project.buildGradleKts)
        assertNull(project.pluginYml)
        // 无主类 / 无 Kotlin 源码 / 无依赖 → 对应节省略
        assertFalse(project.buildGradleKts.contains("application"))
        assertFalse(project.buildGradleKts.contains("kotlin(\"jvm\")"))
        assertFalse(project.buildGradleKts.contains("dependencies {"))
    }

    @Test
    fun `mixed project generates settings build and plugin yml`() {
        val config = BuildConfig(
            name = "MyServer",
            version = "1.0.0",
            sourceKotlin = "kotlin",
            sourceJava = "java",
            mainClass = "com.example.Main",
            mavenDeps = listOf(MavenCoord("com.example", "shared-api", "2.0")),
            paperVersion = "1.21",
            pluginsEnabled = setOf("minecraft"),
        )
        val project = generator.generate(config)

        GoldenFile().assertMatches("gradle.settings.mixed.txt", project.settingsGradle)
        GoldenFile().assertMatches("gradle.build.mixed.txt", project.buildGradleKts)
        GoldenFile().assertMatches("gradle.plugin.yml.txt", assertNotNull(project.pluginYml))
    }

    @Test
    fun `minecraft only project falls back to project name as plugin main`() {
        val config = BuildConfig(
            name = "HomePlugin",
            version = "1.0",
            pluginsEnabled = setOf("minecraft"),
            paperVersion = "1.21",
        )
        val project = generator.generate(config)

        GoldenFile().assertMatches("gradle.build.minecraft.txt", project.buildGradleKts)
        assertTrue(assertNotNull(project.pluginYml).contains("main: \"HomePlugin\""))
    }
}
