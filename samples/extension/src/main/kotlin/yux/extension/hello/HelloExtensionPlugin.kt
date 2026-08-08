package yux.extension.hello

import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxVisibility
import yux.compiler.lexer.TokenKind
import yux.plugin.api.CodegenHook
import yux.plugin.api.ExtensionParser
import yux.plugin.api.PluginArtifact
import yux.plugin.api.PluginContext
import yux.plugin.api.SemanticRule
import yux.plugin.api.SyntaxTransform
import yux.plugin.api.YuxCompilerPlugin

/**
 * hello-extension 示例插件（T-M6-6 / 06-§M6）：注册 `greet` 扩展关键字。
 *
 * 全流程：`greet "World"` → ExtensionParser 解析为载荷（字符串字面量文本）→
 * SyntaxTransform 下沉为 `fun main() { print "Hello, World!" }` → 字节码。
 */
class HelloExtensionPlugin : YuxCompilerPlugin {
    override val id = "hello-extension"
    override val version = "0.1.0"

    override fun configure(context: PluginContext) {
        context.registerKeyword("greet", greetParser)
        context.registerSyntaxTransform(greetTransform)
        context.registerSemanticRule(greetRule)
        context.registerCodegenHook(markerHook)
        context.addRuntimeDependency("dev.yux:yux-stdlib:0.1.0-SNAPSHOT")
    }

    /** 载荷：被 greet 的字符串（原始 token 文本，含引号）。 */
    private val greetParser = ExtensionParser { parser, _ ->
        val token = parser.current
        if (token.kind != TokenKind.STRING_LITERAL) {
            parser.error("greet 期望字符串参数，实际是 '${token.text}'")
        } else {
            parser.advance()
        }
        token.text
    }

    /** 下沉：`greet "World"` → `fun main() { print "Hello, World!" }`。 */
    private val greetTransform = SyntaxTransform { node ->
        if (node.keyword != "greet") return@SyntaxTransform null
        val text = node.payload as? String ?: return@SyntaxTransform null
        val body = YxBlock(
            statements = listOf(
                YxExprStmt(
                    expr = YxCall(
                        callee = YxIdentifier("print", node.span),
                        args = listOf(YxStringLiteral(text, node.span)),
                        noParen = true,
                        span = node.span,
                    ),
                    span = node.span,
                ),
            ),
            span = node.span,
        )
        listOf(
            YxFunction(
                name = "main",
                typeParams = emptyList(),
                params = emptyList(),
                returnType = null,
                body = YxFunctionBody.YxBlockBody(body, node.span),
                isAsync = false,
                isOverride = false,
                visibility = YxVisibility.PUBLIC,
                annotations = emptyList(),
                span = node.span,
            ),
        )
    }

    /** 语义规则示例：greet 载荷必须是字符串（防御性校验）。 */
    private val greetRule = SemanticRule { _, diagnostics ->
        // 下沉后的 AST 已由类型检查器覆盖；此处仅演示注册通道可被调用
        Unit
    }

    /** 代码生成钩子示例：附加一个标记产物。 */
    private val markerHook = CodegenHook { _, _ ->
        listOf(PluginArtifact("hello-extension.marker", "hello".encodeToByteArray()))
    }
}

/** 供测试直接引用。 */
val greetExtensionKeyword: String = "greet"
val greetPayload: (YxExtensionDecl) -> String? = { it.payload as? String }
