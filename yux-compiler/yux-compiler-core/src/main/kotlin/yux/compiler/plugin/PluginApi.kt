package yux.compiler.plugin

import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrModule
import yux.compiler.lexer.Token
import yux.compiler.parser.Parser
import yux.compiler.sema.AnalysisResult

/**
 * 编译器插件（02-§10.1 / T-M6-1）：通过 [configure] 向 [PluginContext] 注册扩展能力。
 *
 * 插件通过 `ServiceLoader`（META-INF/services/yux.compiler.plugin.YuxCompilerPlugin）发现，
 * CLI 以 `--plugin <jar>` 加载；插件仅依赖本 SPI 与 core 的公开类型（02-§10.3）。
 */
interface YuxCompilerPlugin {
    val id: String
    val version: String

    fun configure(context: PluginContext)
}

/** 插件上下文：扩展注册通道（02-§10.2 / T-M6-2）。 */
interface PluginContext {
    /** 注册扩展关键字与解析函数（如 `event`）；Parser 在钩子处暂停并委托（T-M6-4）。 */
    fun registerKeyword(keyword: String, parser: ExtensionParser)

    /** 注册语法下沉变换：将扩展节点变换为普通 AST（T-M6-5）。 */
    fun registerSyntaxTransform(transform: SyntaxTransform)

    /** 注册语义规则：领域约束检查。 */
    fun registerSemanticRule(rule: SemanticRule)

    /** 注册代码生成钩子：在 JVM 后端生成阶段附加产物（如 plugin.yml）。 */
    fun registerCodegenHook(hook: CodegenHook)

    /** 声明运行时依赖坐标（注入生成的 Gradle 构建文件）。 */
    fun addRuntimeDependency(module: String)
}

/**
 * 扩展关键字解析函数（02-§10.2.1）：[Parser] 遇到已注册关键字时暂停并委托，
 * 返回不透明载荷（由插件自定义，最终经 [SyntaxTransform] 下沉为普通 AST）。
 */
fun interface ExtensionParser {
    fun parse(parser: Parser, keyword: Token): Any?
}

/** 语法下沉（02-§10.2.2）：`YxExtensionDecl` → 普通 AST 声明列表；返回 null 表示不处理该节点。 */
fun interface SyntaxTransform {
    fun transform(node: YxExtensionDecl): List<YxDecl>?
}

/** 语义规则（02-§10.2.3）：领域约束检查，在语义分析后执行。 */
fun interface SemanticRule {
    fun check(analysis: AnalysisResult, diagnostics: DiagnosticSink)
}

/** 代码生成钩子（02-§10.2.4）：附加产物（插件 jar / plugin.yml 等）。 */
fun interface CodegenHook {
    fun generate(module: IrModule, diagnostics: DiagnosticSink): List<PluginArtifact>
}

/** 代码生成钩子产物：类名（或路径）→ 字节。 */
data class PluginArtifact(
    val name: String,
    val bytes: ByteArray,
)
