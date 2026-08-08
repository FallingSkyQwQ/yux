package yux.buildtool

import org.junit.jupiter.api.Test
import yux.testsupport.GoldenFile
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GradleGenerator 生成文本的 golden 快照测试（T-M7-2）。 */
class GradleGeneratorTest {

    private val generator = GradleGenerator()

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
        assertTrue(assertNotNull(project.pluginYml).contains("main: HomePlugin"))
    }
}
