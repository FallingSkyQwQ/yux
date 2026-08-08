package yux.compiler.lowering

import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
import yux.compiler.plugin.PluginManager

/**
 * 插件下沉管线（T-M6-5 / 02-§10.4）：在 Lowering 前将全部 [YxExtensionDecl]
 * 经插件注册的 SyntaxTransform 变换为普通 AST；未匹配时报错，保证后端不可见领域语法。
 */
class PluginLowering(
    private val pluginManager: PluginManager,
    private val diagnostics: DiagnosticSink,
) {
    fun lower(declsByFile: Map<String, List<YxDecl>>): Map<String, List<YxDecl>> {
        val result = linkedMapOf<String, List<YxDecl>>()
        for ((path, decls) in declsByFile) {
            val lowered = mutableListOf<YxDecl>()
            for (decl in decls) {
                if (decl is YxExtensionDecl) {
                    lowered += sink(decl)
                } else {
                    lowered += decl
                }
            }
            result[path] = lowered
        }
        return result
    }

    private fun sink(node: YxExtensionDecl): List<YxDecl> {
        for (transform in pluginManager.transforms) {
            val transformed = transform.transform(node)
            if (transformed != null) {
                // 变换产物中不允许残留扩展节点（02-§10.4：后端不可见领域语法）
                val leftover = transformed.firstOrNull { it is YxExtensionDecl }
                if (leftover != null) {
                    diagnostics.error(
                        "下沉产物仍含扩展节点 '${(leftover as YxExtensionDecl).keyword}'",
                        leftover.span.start,
                        ErrorCodes.EXTENSION_NOT_LOWERED,
                    )
                    return emptyList()
                }
                return transformed
            }
        }
        diagnostics.error(
            "扩展节点 '${node.keyword}'（插件 ${node.pluginId}）未下沉: 无匹配 SyntaxTransform",
            node.span.start,
            ErrorCodes.EXTENSION_NOT_LOWERED,
        )
        return emptyList()
    }
}
