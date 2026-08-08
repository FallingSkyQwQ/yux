package yux.extension.hello

import org.junit.jupiter.api.Test
import yux.compiler.Compiler
import yux.compiler.diag.DiagnosticSink
import yux.compiler.irgen.IRGen
import yux.compiler.plugin.PluginManager
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelloExtensionTest {

    private fun pluginManager(): PluginManager {
        val manager = PluginManager()
        manager.register(HelloExtensionPlugin())
        assertFalse(manager.diagnostics.hasErrors, manager.diagnostics.diagnostics.toString())
        return manager
    }

    @Test
    fun `registration channels are invoked`() {
        val manager = pluginManager()
        assertTrue("greet" in manager.registeredKeywords)
        assertEquals(listOf("hello-extension"), manager.registeredPlugins)
        assertEquals(1, manager.transforms.size)
        assertEquals(1, manager.rules.size)
        assertEquals(1, manager.hooks.size)
        assertEquals(listOf("dev.yux:yux-stdlib:0.1.0-SNAPSHOT"), manager.dependencies)
    }

    @Test
    fun `extension keyword parses and sinks without leak`() {
        val source = SourceFile("greet.yux", "greet \"Hello, Yux plugin!\"\n")
        val compiler = Compiler(DiagnosticSink(), pluginManager())
        val decls = compiler.parseToDecls(source)
        assertFalse(compiler.diagnostics.hasErrors, compiler.diagnostics.diagnostics.toString())
        // 下沉后：扩展节点已替换为 fun main()
        assertTrue(decls.none { it is yux.compiler.ast.YxExtensionDecl }, "扩展节点必须全部下沉")
        assertTrue(decls.any { it is yux.compiler.ast.YxFunction && it.name == "main" }, "应生成 main 函数")
    }

    @Test
    fun `sunk output passes sema and IR gen`() {
        val source = SourceFile("greet.yux", "greet \"Hello, Yux plugin!\"\n")
        val compiler = Compiler(DiagnosticSink(), pluginManager())
        val decls = compiler.parseToDecls(source)
        val analysis = SemanticAnalyzer().analyze(mapOf(source.path to decls), compiler.diagnostics)
        assertFalse(compiler.diagnostics.hasErrors, compiler.diagnostics.diagnostics.toString())
        val module = IRGen(analysis).generate(mapOf(source.path to decls))
        val main = module.classes.first { it.isFileClass }.methods.firstOrNull { it.name == "main" }
        assertTrue(main != null, "IR 应包含 main 方法")
    }
}
