package yux.compiler.plugin

import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
import yux.compiler.lexer.Keywords

/** 关键字 → 插件解析器的绑定（含注册插件 id，用于冲突定位）。 */
data class KeywordBinding(
    val pluginId: String,
    val parser: ExtensionParser,
)

/**
 * 插件注册表（T-M6-2/T-M6-3）：加载并校验插件，聚合关键字/下沉/语义规则/代码生成钩子。
 *
 * 约束（02-§10.4）：扩展关键字必须唯一（冲突报错）；禁止注册内置关键字（隔离性）。
 */
class PluginManager(
    val diagnostics: DiagnosticSink = DiagnosticSink(),
) : PluginContext {

    private val pluginIds = mutableSetOf<String>()
    private val keywordBindings = linkedMapOf<String, KeywordBinding>()
    private val syntaxTransforms = mutableListOf<SyntaxTransform>()
    private val semanticRules = mutableListOf<SemanticRule>()
    private val codegenHooks = mutableListOf<CodegenHook>()
    private val runtimeDependencies = mutableListOf<String>()

    val registeredIds: Set<String> get() = pluginIds
    val registeredKeywords: Set<String> get() = keywordBindings.keys
    val registeredPlugins: List<String> get() = pluginIds.toList()

    /** 注册一个插件：configure 前先做 id 冲突校验。 */
    fun register(plugin: YuxCompilerPlugin) {
        if (!pluginIds.add(plugin.id)) {
            diagnostics.error(
                "插件 id 冲突: '${plugin.id}' 已注册",
                null,
                ErrorCodes.DUPLICATE_PLUGIN_ID,
            )
            return
        }
        plugin.configure(this)
    }

    /** 查找扩展关键字对应的解析器（未注册返回 null）。 */
    fun parserFor(text: String): KeywordBinding? = keywordBindings[text]

    val transforms: List<SyntaxTransform> get() = syntaxTransforms
    val rules: List<SemanticRule> get() = semanticRules
    val hooks: List<CodegenHook> get() = codegenHooks
    val dependencies: List<String> get() = runtimeDependencies

    // ── PluginContext 实现（02-§10.2）───────────────────────────────────────

    override fun registerKeyword(keyword: String, parser: ExtensionParser) {
        val pluginId = pluginIds.lastOrNull() ?: "?"
        if (keyword in Keywords.BASE || keyword in Keywords.SOFT) {
            diagnostics.error(
                "插件 '$pluginId' 注册了内置关键字 '$keyword'（02-§10.4 隔离性）",
                null,
                ErrorCodes.RESERVED_EXTENSION_KEYWORD,
            )
            return
        }
        val existing = keywordBindings[keyword]
        if (existing != null) {
            diagnostics.error(
                "扩展关键字 '$keyword' 冲突: 插件 '${existing.pluginId}' 已注册",
                null,
                ErrorCodes.DUPLICATE_EXTENSION_KEYWORD,
            )
            return
        }
        keywordBindings[keyword] = KeywordBinding(pluginId, parser)
    }

    override fun registerSyntaxTransform(transform: SyntaxTransform) {
        syntaxTransforms += transform
    }

    override fun registerSemanticRule(rule: SemanticRule) {
        semanticRules += rule
    }

    override fun registerCodegenHook(hook: CodegenHook) {
        codegenHooks += hook
    }

    override fun addRuntimeDependency(module: String) {
        runtimeDependencies += module
    }
}
