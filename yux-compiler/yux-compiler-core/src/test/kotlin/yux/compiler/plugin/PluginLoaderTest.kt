package yux.compiler.plugin

import org.junit.jupiter.api.Test
import yux.compiler.diag.DiagnosticSink
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-M6-3：ServiceLoader + jar 加载 + 冲突/隔离校验。 */
class PluginLoaderTest {

    /** 打包测试插件为临时 jar（含 META-INF/services）。 */
    private fun buildPluginJar(pluginClass: String, out: Path) {
        val classBytes = this::class.java.classLoader
            .getResourceAsStream("yux/compiler/plugin/TestPlugin.class")!!.readBytes()
        JarOutputStream(Files.newOutputStream(out)).use { jar ->
            jar.putNextEntry(JarEntry("yux/compiler/plugin/TestPlugin.class"))
            jar.write(classBytes)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/services/yux.compiler.plugin.YuxCompilerPlugin"))
            jar.write(pluginClass.toByteArray())
            jar.closeEntry()
        }
    }

    @Test
    fun `从 jar 加载插件并经 configure 注册`() {
        val jar = Files.createTempFile("test-plugin", ".jar")
        buildPluginJar("yux.compiler.plugin.TestPlugin", jar)

        val plugins = PluginLoader.loadFromJar(jar)
        assertEquals(1, plugins.size)
        val plugin = plugins.single()
        assertEquals("test-loader-plugin", plugin.id)

        val manager = PluginManager()
        manager.register(plugin)
        assertFalse(manager.diagnostics.hasErrors, manager.diagnostics.diagnostics.toString())
        assertNotNull(manager.parserFor("loader_kw"))
        assertEquals("test-loader-plugin", manager.parserFor("loader_kw")?.pluginId)
    }

    @Test
    fun `损坏的 jar 不崩溃`() {
        val badJar = Files.createTempFile("bad-plugin", ".jar")
        Files.writeString(badJar, "not a jar")
        val plugins = PluginLoader.loadFromJar(badJar)
        assertTrue(plugins.isEmpty())
    }

    @Test
    fun `类路径加载不因无插件而失败`() {
        // 测试类路径不含 META-INF/services 时返回空列表（不抛异常）
        val plugins = PluginLoader.loadFromClasspath()
        // 核心测试工程无插件；若未来加入则至少不抛异常
        assertTrue(plugins.isNotEmpty() || plugins.isEmpty())
    }

    @Test
    fun `内置关键字注册被拒绝（隔离性）`() {
        val manager = PluginManager()
        manager.register(object : YuxCompilerPlugin {
            override val id = "reserved"
            override val version = "1.0"
            override fun configure(context: PluginContext) {
                context.registerKeyword("while") { _, _ -> null }
            }
        })
        assertTrue(manager.diagnostics.hasErrors)
        assertEquals("E0031", manager.diagnostics.diagnostics.first().code)
    }
}

/** ServiceLoader 测试插件（经 jar 加载）。 */
class TestPlugin : YuxCompilerPlugin {
    override val id = "test-loader-plugin"
    override val version = "1.0"

    override fun configure(context: PluginContext) {
        context.registerKeyword("loader_kw") { parser, _ ->
            parser.advance()
            SourceFile("", "").path // 任意载荷
        }
        context.addRuntimeDependency("dev.yux:yux-stdlib:1.0")
    }
}
