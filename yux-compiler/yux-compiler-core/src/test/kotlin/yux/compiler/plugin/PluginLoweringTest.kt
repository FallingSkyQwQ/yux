package yux.compiler.plugin

import org.junit.jupiter.api.Test
import yux.compiler.Compiler
import yux.compiler.ast.YxFunction
import yux.compiler.diag.ErrorCodes
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T-M6-5：插件下沉管线——扩展节点在 Lowering 前全部下沉，无泄漏到后端。 */
class PluginLoweringTest {

    private class GreetPlugin : YuxCompilerPlugin {
        override val id = "greet-plugin"
        override val version = "1.0"
        override fun configure(context: PluginContext) {
            context.registerKeyword("greet") { parser, _ ->
                val text = parser.current.text
                parser.advance()
                text
            }
            context.registerSyntaxTransform { node ->
                if (node.keyword != "greet") return@registerSyntaxTransform null
                val text = node.payload as String
                listOf(
                    YxFunction(
                        name = "main",
                        typeParams = emptyList(),
                        params = emptyList(),
                        returnType = null,
                        body = yux.compiler.ast.YxFunctionBody.YxBlockBody(
                            yux.compiler.ast.YxBlock(
                                listOf(
                                    yux.compiler.ast.YxExprStmt(
                                        yux.compiler.ast.YxCall(
                                            yux.compiler.ast.YxIdentifier("print", node.span),
                                            listOf(yux.compiler.ast.YxStringLiteral(text, node.span)),
                                            noParen = true,
                                            node.span,
                                        ),
                                        node.span,
                                    ),
                                ),
                                node.span,
                            ),
                            node.span,
                        ),
                        isAsync = false,
                        isOverride = false,
                        visibility = yux.compiler.ast.YxVisibility.PUBLIC,
                        annotations = emptyList(),
                        span = node.span,
                    ),
                )
            }
        }
    }

    @Test
    fun `greet 节点下沉为 fun main 且后端不可见`() {
        val source = SourceFile("greet.yux", "greet \"Hello\"\n")
        val compiler = Compiler(
            pluginManager = PluginManager().apply { register(GreetPlugin()) },
        )
        val decls = compiler.parseToDecls(source)
        assertFalse(compiler.diagnostics.hasErrors, compiler.diagnostics.diagnostics.toString())
        // 下沉后无扩展节点残留
        assertTrue(decls.none { it is yux.compiler.ast.YxExtensionDecl })
        val main = decls.filterIsInstance<YxFunction>().single()
        assertEquals("main", main.name)
        // 全链路：语义分析 + IR 生成均可通过（无泄漏到后端）
        val analysis = compiler.analyze(mapOf(source.path to decls))
        assertFalse(compiler.diagnostics.hasErrors, compiler.diagnostics.diagnostics.toString())
        val module = compiler.generate(mapOf(source.path to decls), analysis)
        assertTrue(module.classes.any { it.isFileClass })
        assertTrue(
            module.classes.flatMap { it.methods }.any { it.name == "main" },
            "IR 应包含下沉生成的 main 方法",
        )
    }

    @Test
    fun `未注册下沉变换的扩展节点报 E0032`() {
        val manager = PluginManager()
        manager.register(object : YuxCompilerPlugin {
            override val id = "no-transform"
            override val version = "1.0"
            override fun configure(context: PluginContext) {
                context.registerKeyword("orphan") { parser, _ ->
                    parser.advance()
                    null
                }
            }
        })
        val source = SourceFile("orphan.yux", "orphan 42\n")
        val compiler = Compiler(pluginManager = manager)
        val decls = compiler.parseToDecls(source)
        assertTrue(compiler.diagnostics.hasErrors)
        val error = compiler.diagnostics.diagnostics.first { it.code == ErrorCodes.EXTENSION_NOT_LOWERED }
        assertIs<yux.compiler.diag.Diagnostic>(error)
        assertTrue(error.message.contains("orphan"))
    }
}
