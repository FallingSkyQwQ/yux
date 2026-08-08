package yux.plugin.api

import org.junit.jupiter.api.Test
import yux.compiler.plugin.PluginManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-M6-1：SPI 接口可编译——插件作者以 `yux.plugin.api` 命名空间实现接口，
 * 注册通道经 PluginManager 生效。
 */
class PluginApiTest {

    /** 以 yux.plugin.api 别名实现的测试插件。 */
    private class GreetPlugin : YuxCompilerPlugin {
        override val id = "api-greet"
        override val version = "1.0"

        override fun configure(context: PluginContext) {
            context.registerKeyword("api_greet") { parser, _ ->
                val text = parser.current.text
                parser.advance()
                text
            }
            context.registerSyntaxTransform { null }
            context.registerSemanticRule { _, _ -> }
            context.registerCodegenHook { _, _ -> emptyList() }
            context.addRuntimeDependency("dev.yux:yux-stdlib:1.0")
        }
    }

    @Test
    fun `插件经别名实现并注册成功`() {
        val manager = PluginManager()
        manager.register(GreetPlugin())
        assertFalse(manager.diagnostics.hasErrors, manager.diagnostics.diagnostics.toString())
        assertNotNull(manager.parserFor("api_greet"))
        assertEquals(listOf("api-greet"), manager.registeredPlugins)
        assertEquals(1, manager.transforms.size)
        assertEquals(1, manager.rules.size)
        assertEquals(1, manager.hooks.size)
        assertEquals(listOf("dev.yux:yux-stdlib:1.0"), manager.dependencies)
    }

    @Test
    fun `别名类型与核心类型一致`() {
        // typealias 指向同一类型：别名实现可被核心接口引用
        val plugin: yux.compiler.plugin.YuxCompilerPlugin = GreetPlugin()
        assertTrue(plugin is YuxCompilerPlugin)
        assertEquals("api-greet", plugin.id)
    }
}
