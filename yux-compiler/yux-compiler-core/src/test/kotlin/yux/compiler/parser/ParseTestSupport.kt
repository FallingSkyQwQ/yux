package yux.compiler.parser

import yux.compiler.ast.YxDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.Severity
import yux.compiler.source.SourceFile

/** 解析测试辅助：文本 → AST + 诊断。 */
object ParseTestSupport {

    class Result(
        val decls: List<YxDecl>,
        val diagnostics: DiagnosticSink,
    ) {
        val errors: List<String> get() =
            diagnostics.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }
        val hasErrors: Boolean get() = diagnostics.hasErrors
    }

    fun parse(text: String, path: String = "main.yux"): Result {
        val diagnostics = DiagnosticSink()
        val parser = Parser(SourceFile(path, text), diagnostics)
        val program = parser.parse()
        val decls = CstToAst().convert(program)
        return Result(decls, diagnostics)
    }

    /** AST dump（golden 格式）。 */
    fun dump(text: String, path: String = "main.yux"): String {
        val r = parse(text, path)
        if (r.hasErrors) {
            throw AssertionError("Parse failed with errors: ${r.diagnostics.diagnostics}")
        }
        return AstPrinter.dump(r.decls)
    }
}
