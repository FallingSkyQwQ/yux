package yux.compiler.sema

import yux.compiler.ast.YxExpr
import yux.compiler.diag.Diagnostic

/**
 * 语义分析结果的稳定文本 dump（供 `yuxc check` 与 golden 快照）。
 */
object AnalysisPrinter {

    /** 按表达式出现顺序输出类型推导结果。 */
    fun dumpTypes(exprTypes: Map<YxExpr, SemaType>): String =
        buildString {
            exprTypes.entries.forEach { (expr, type) ->
                append(expr.javaClass.simpleName.removePrefix("Yx"))
                append(" : ").append(type.render()).append('\n')
            }
        }

    /** 按 02-§12.1 格式输出诊断。 */
    fun printDiagnostics(diagnostics: List<Diagnostic>): String = buildString {
        for (d in diagnostics) {
            val code = d.code?.let { "[$it]" } ?: ""
            val pos = d.position?.let { "${it.line}:${it.column}" } ?: "-"
            append("${d.severity}$code $pos: ${d.message}\n")
        }
    }
}
