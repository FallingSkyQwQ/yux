package yux.compiler

import yux.compiler.ast.YxDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrModule
import yux.compiler.irgen.AsyncCpsLowering
import yux.compiler.irgen.IRGen
import yux.compiler.lowering.PluginLowering
import yux.compiler.optimizer.BasicOpt
import yux.compiler.optimizer.SsaOpt
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.plugin.PluginManager
import yux.compiler.sema.AnalysisResult
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile

/**
 * 编译管线（M6 起统一入口）：解析 → 插件下沉 → 语义分析 → IR 生成 → 最小优化。
 *
 * 插件注入点（02-§3 Pipeline）：
 * - Parser 构造携带 [PluginManager]（扩展关键字钩子，T-M6-4）；
 * - CstToAst 之后运行 [PluginLowering]（扩展节点下沉，T-M6-5）；
 * - 语义分析之后运行插件 SemanticRule；
 * - IR 生成后运行插件 CodegenHook（产物由后端/CLI 汇总）。
 */
class Compiler(
    val diagnostics: DiagnosticSink = DiagnosticSink(),
    val pluginManager: PluginManager = PluginManager(),
    /** 外部类解析加载器（M10 混合项目：项目 Java/Kotlin 编译产物目录的 URLClassLoader）。 */
    val classLoader: ClassLoader = ClassPathSymbolProvider::class.java.classLoader,
) {

    /** 解析单个源文件为 AST（含插件下沉）。 */
    fun parseToDecls(source: SourceFile): List<YxDecl> {
        val program = Parser(source, diagnostics, pluginManager).parse()
        val decls = CstToAst().convert(program)
        val lowered = PluginLowering(pluginManager, diagnostics)
            .lower(mapOf(source.path to decls))
        return lowered[source.path] ?: decls
    }

    /** 语义分析（含插件语义规则）。 */
    fun analyze(declsByFile: Map<String, List<YxDecl>>): AnalysisResult {
        val analysis = SemanticAnalyzer(classLoader).analyze(declsByFile, diagnostics)
        for (rule in pluginManager.rules) {
            rule.check(analysis, diagnostics)
        }
        return analysis
    }

    /** IR 生成 + async CPS 降级 + SSA 优化 + 最小优化。 */
    fun generate(declsByFile: Map<String, List<YxDecl>>, analysis: AnalysisResult): IrModule =
        generateProject(declsByFile, analysis, bodyFiles = null).module

    /**
     * 类级增量（M13）：返回 IR 模块 + 文件→产出类名映射。
     * [bodyFiles] 非 null 时仅这些文件的类生成方法体（骨架遍注册全部类供跨文件引用），
     * 其余文件的类字节由调用方从缓存复用。
     */
    fun generateProject(
        declsByFile: Map<String, List<YxDecl>>,
        analysis: AnalysisResult,
        bodyFiles: Set<String>?,
    ): IrGenerationResult {
        val irGen = IRGen(analysis, classLoader, bodyFiles = bodyFiles)
        val module = irGen.generate(declsByFile)
        // T-M14：async fun → CPS 状态机（门面/挂起入口/状态机类）；必须在优化前（await 尚未消费）
        AsyncCpsLowering(module).transform()
        // 完整 SSA 优化（CFG/φ/SCCP/拷贝传播/激进 DCE/φ 销毁）
        SsaOpt.optimize(module)
        // 最小优化收尾（语句级常量折叠、死代码、冗余跳转、空守卫折叠）
        BasicOpt.optimize(module)
        return IrGenerationResult(module, irGen.completedFileClassNames(module))
    }
}

/** IR 生成结果（类级增量）：模块 + 文件 → 产出类名映射。 */
data class IrGenerationResult(
    val module: IrModule,
    val fileClassNames: Map<String, Set<String>>,
)
