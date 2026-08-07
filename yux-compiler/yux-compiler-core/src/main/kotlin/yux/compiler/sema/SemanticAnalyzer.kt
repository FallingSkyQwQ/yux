package yux.compiler.sema

import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxNode
import yux.compiler.diag.Diagnostic
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.Severity
import yux.compiler.lowering.GuardPoint

/**
 * 语义分析编排入口（T-M3 总装）：
 *
 * 1. [SymbolTable.collect] 声明收集遍（02-§6.2）；
 * 2. [Declarations.process] 成员解析 + data/service/override/注解校验；
 * 3. [TypeChecker.checkAll] 类型检查 + 推断 + 智能转型 + 空守卫。
 */
class SemanticAnalyzer(
    private val classPath: ClassPathSymbolProvider = ClassPathSymbolProvider(),
) {
    fun analyze(declsByFile: Map<String, List<YxDecl>>, diagnostics: DiagnosticSink): AnalysisResult {
        val symbolTable = SymbolTable(classPath)
        symbolTable.collect(declsByFile, diagnostics)
        val typeResolver = TypeResolver(symbolTable, classPath, diagnostics)
        val inference = Inference(diagnostics)
        Declarations(symbolTable, classPath, typeResolver, diagnostics).process(declsByFile)
        val checker = TypeChecker(symbolTable, classPath, typeResolver, inference, diagnostics)
        checker.checkAll(declsByFile)
        return AnalysisResult(
            symbolTable = symbolTable,
            exprTypes = checker.exprTypesView,
            declTypes = checker.declTypesView,
            resolvedRefs = checker.resolvedRefsView,
            guardPoints = checker.guardPoints,
            diagnostics = diagnostics,
        )
    }
}

/** 语义分析结果：表达式/声明类型、名称解析、空守卫插入点、诊断。 */
class AnalysisResult(
    val symbolTable: SymbolTable,
    val exprTypes: Map<YxExpr, SemaType>,
    val declTypes: Map<YxDecl, SemaType>,
    val resolvedRefs: Map<YxNode, Symbol>,
    val guardPoints: List<GuardPoint>,
    val diagnostics: DiagnosticSink,
) {
    val errors: List<Diagnostic> get() = diagnostics.diagnostics.filter { it.severity == Severity.ERROR }
    val hasErrors: Boolean get() = diagnostics.hasErrors
}
