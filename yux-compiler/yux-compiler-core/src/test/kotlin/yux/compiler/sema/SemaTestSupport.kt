package yux.compiler.sema

import yux.compiler.diag.Diagnostic
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.Severity
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.source.SourceFile

/**
 * 语义分析测试辅助：源码文本 → [AnalysisResult]。
 * 解析阶段诊断与语义阶段诊断合并暴露。
 */
object SemaTestSupport {

    class Result(
        val analysis: AnalysisResult,
    ) {
        val diagnostics: DiagnosticSink get() = analysis.diagnostics
        val errors: List<Diagnostic> get() = analysis.errors
        val errorCodes: List<String> get() = errors.map { it.code ?: "NO_CODE" }
        val hasErrors: Boolean get() = analysis.hasErrors
        val reminds: List<Diagnostic> get() = diagnostics.diagnostics.filter { it.severity == Severity.REMIND }
        val warnings: List<Diagnostic> get() = diagnostics.diagnostics.filter { it.severity == Severity.WARNING }

        fun hasCode(code: String): Boolean = diagnostics.diagnostics.any { it.code == code }
        fun countCode(code: String): Int = diagnostics.diagnostics.count { it.code == code }
    }

    fun analyze(text: String, path: String = "main.yux"): Result {
        val (declsByFile, parseDiags) = parse(mapOf(path to text))
        val diags = DiagnosticSink()
        val result = SemanticAnalyzer().analyze(declsByFile, diags)
        parseDiags.forEach { diags.report(it) }
        return Result(result)
    }

    fun analyzeAll(vararg sources: Pair<String, String>): Result {
        val (declsByFile, parseDiags) = parse(sources.toMap())
        val diags = DiagnosticSink()
        val result = SemanticAnalyzer().analyze(declsByFile, diags)
        parseDiags.forEach { diags.report(it) }
        return Result(result)
    }

    private fun parse(declsByFile: Map<String, String>): Pair<Map<String, List<yux.compiler.ast.YxDecl>>, List<Diagnostic>> {
        val out = mutableMapOf<String, List<yux.compiler.ast.YxDecl>>()
        val parseDiags = mutableListOf<Diagnostic>()
        for ((path, text) in declsByFile) {
            val diags = DiagnosticSink()
            val parser = Parser(SourceFile(path, text), diags)
            val program = parser.parse()
            val decls = CstToAst().convert(program)
            parseDiags += diags.diagnostics
            out[path] = decls
        }
        return out to parseDiags
    }
}
