package yux.compiler.plugin

import org.junit.jupiter.api.Test
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T-M6-4：扩展关键字在 Parser 钩子处暂停并委托。 */
class ParserExtensionTest {

    private fun managerWith(keyword: String, parser: ExtensionParser): PluginManager {
        val manager = PluginManager()
        manager.register(object : YuxCompilerPlugin {
            override val id = "test-plugin"
            override val version = "1.0"
            override fun configure(context: PluginContext) {
                context.registerKeyword(keyword, parser)
            }
        })
        assertFalse(manager.diagnostics.hasErrors, manager.diagnostics.diagnostics.toString())
        return manager
    }

    @Test
    fun `注入 event 关键字可解析为扩展节点`() {
        val manager = managerWith("event") { parser, keyword ->
            val name = parser.expectIdent("event name")
            parser.skipNewlines()
            "event:${name.text}"
        }
        val source = SourceFile("main.yux", "event PlayerJoin\nfun main() {}\n")
        val parser = Parser(source, pluginManager = manager)
        val program = parser.parse()
        assertFalse(parser.diagnostics.hasErrors, parser.diagnostics.diagnostics.toString())
        val decls = CstToAst().convert(program)
        val ext = decls.filterIsInstance<YxExtensionDecl>().single()
        assertEquals("event", ext.keyword)
        assertEquals("test-plugin", ext.pluginId)
        assertEquals("event:PlayerJoin", ext.payload)
    }

    @Test
    fun `无插件时不拦截普通标识符声明`() {
        val source = SourceFile("main.yux", "Player {}\n")
        val parser = Parser(source)
        val program = parser.parse()
        assertFalse(parser.diagnostics.hasErrors, parser.diagnostics.diagnostics.toString())
        val decls = CstToAst().convert(program)
        assertTrue(decls.none { it is YxExtensionDecl })
        assertEquals(1, decls.size)
    }

    @Test
    fun `关键字带参数与块体`() {
        val manager = managerWith("greet") { parser, _ ->
            val arg = parser.parsePrimaryExpr()
            parser.finishStatement()
            "payload:${arg.span.start.line}"
        }
        val source = SourceFile("main.yux", "greet \"hi\"\n")
        val parser = Parser(source, pluginManager = manager)
        val program = parser.parse()
        assertFalse(parser.diagnostics.hasErrors, parser.diagnostics.diagnostics.toString())
        val decls = CstToAst().convert(program)
        val ext = decls.filterIsInstance<YxExtensionDecl>().single()
        assertIs<YxExtensionDecl>(ext)
        assertEquals("greet", ext.keyword)
    }
}
