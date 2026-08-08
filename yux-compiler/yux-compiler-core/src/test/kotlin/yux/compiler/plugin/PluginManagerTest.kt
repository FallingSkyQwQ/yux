package yux.compiler.plugin

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import yux.compiler.diag.Severity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-M6-2：注册通道与冲突/隔离校验。 */
class PluginManagerTest {

    private class RecorderPlugin(
        override val id: String,
        private val keyword: String? = null,
    ) : YuxCompilerPlugin {
        override val version = "1.0"
        var keywordRegistered = false
        var transformRegistered = false
        var ruleRegistered = false
        var hookRegistered = false
        var dependencyAdded = false

        override fun configure(context: PluginContext) {
            if (keyword != null) {
                context.registerKeyword(keyword) { _, _ -> null }
                keywordRegistered = true
            }
            context.registerSyntaxTransform { null }
            transformRegistered = true
            context.registerSemanticRule { _, _ -> }
            ruleRegistered = true
            context.registerCodegenHook { _, _ -> emptyList() }
            hookRegistered = true
            context.addRuntimeDependency("dev.yux:yux-stdlib:1.0")
            dependencyAdded = true
        }
    }

    @Test
    fun `注册通道调用链被记录`() {
        val plugin = RecorderPlugin("recorder", keyword = "greet")
        val manager = PluginManager()
        manager.register(plugin)
        assertTrue(plugin.keywordRegistered)
        assertTrue(plugin.transformRegistered)
        assertTrue(plugin.ruleRegistered)
        assertTrue(plugin.hookRegistered)
        assertTrue(plugin.dependencyAdded)
        assertNotNull(manager.parserFor("greet"))
        assertEquals(1, manager.transforms.size)
        assertEquals(1, manager.rules.size)
        assertEquals(1, manager.hooks.size)
        assertEquals(listOf("dev.yux:yux-stdlib:1.0"), manager.dependencies)
    }

    @Test
    fun `插件 id 冲突报 E0029`() {
        val manager = PluginManager()
        manager.register(RecorderPlugin("dup"))
        manager.register(RecorderPlugin("dup"))
        assertTrue(manager.diagnostics.hasErrors)
        assertEquals(ErrorCodes.DUPLICATE_PLUGIN_ID, manager.diagnostics.diagnostics.first().code)
    }

    @Test
    fun `扩展关键字冲突报 E0030`() {
        val manager = PluginManager()
        manager.register(RecorderPlugin("a", keyword = "event"))
        manager.register(RecorderPlugin("b", keyword = "event"))
        assertTrue(manager.diagnostics.hasErrors)
        val error = manager.diagnostics.diagnostics.first { it.code == ErrorCodes.DUPLICATE_EXTENSION_KEYWORD }
        assertNotNull(error)
        assertTrue(error.message.contains("event"))
    }

    @Test
    fun `注册内置关键字报 E0031 且不生效`() {
        val manager = PluginManager()
        manager.register(RecorderPlugin("bad", keyword = "fun"))
        assertTrue(manager.diagnostics.hasErrors)
        assertEquals(ErrorCodes.RESERVED_EXTENSION_KEYWORD, manager.diagnostics.diagnostics.first().code)
        assertNull(manager.parserFor("fun"))
    }

    @Test
    fun `冲突插件不污染注册表`() {
        val manager = PluginManager()
        manager.register(RecorderPlugin("a", keyword = "event"))
        manager.register(RecorderPlugin("b", keyword = "event"))
        // 冲突关键字保持首个注册者的绑定
        assertEquals("a", manager.parserFor("event")?.pluginId)
        assertTrue(manager.diagnostics.diagnostics.any { it.severity == Severity.ERROR })
    }
}
