package yux.compiler.sema

import yux.compiler.ast.YxClass
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxImport
import yux.compiler.ast.YxPackage
import yux.compiler.ast.YxService
import yux.compiler.ast.SourceSpan
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes

/**
 * 符号表（02-§6.1/6.2）：`GlobalScope → FileScope → DeclScope → BlockScope`。
 *
 * 本类负责**声明收集遍**（02-§6.2 第一步）：为每个源文件建立 [FileScope]，
 * 注册顶级类型 / 函数 / 属性，并维护「包 → 类型」全局索引以支持跨文件解析。
 * 类成员与函数体的语义处理见 [Declarations] 与 [TypeChecker]。
 */
class SymbolTable(
    private val classPath: ClassPathSymbolProvider,
) {
    val files = mutableListOf<FileScope>()
    private val typesByPackage = mutableMapOf<String, MutableMap<String, TypeSymbol>>()

    /** 收集全部源文件的顶层声明。 */
    fun collect(declsByFile: Map<String, List<YxDecl>>, diagnostics: DiagnosticSink) {
        // 第一遍：FileScope（包名 + 导入）
        for ((path, decls) in declsByFile) {
            val pkg = decls.filterIsInstance<YxPackage>().firstOrNull()?.name ?: ""
            val file = FileScope(path, pkg)
            file.imports += decls.filterIsInstance<YxImport>()
            files += file
        }
        // 第二遍：注册顶层声明（含同文件冲突检测）
        for ((path, decls) in declsByFile) {
            val file = fileFor(path)
            file.decls += decls
            for (decl in decls) {
                when (decl) {
                    is YxClass -> registerClassShell(file, decl, diagnostics)
                    is YxDataClass -> registerDataShell(file, decl, diagnostics)
                    is YxService -> registerServiceShell(file, decl, diagnostics)
                    else -> Unit
                }
            }
        }
    }

    fun fileFor(path: String): FileScope = files.first { it.path == path }

    /** 同一包下其他文件的同名类型（用于「同包免导入」解析）。 */
    fun typeInPackage(pkg: String, name: String): TypeSymbol? = typesByPackage[pkg]?.get(name)

    /** 已注册的全部类型（诊断/调试用）。 */
    fun allTypeNames(): List<String> = typesByPackage.values.flatMap { it.keys }

    // ── 注册 ────────────────────────────────────────────────────────────────

    private fun registerType(file: FileScope, symbol: TypeSymbol, span: SourceSpan, diagnostics: DiagnosticSink) {
        if (file.types.containsKey(symbol.name)) {
            diagnostics.error(
                "重复声明类型 '${symbol.name}'",
                span.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        val pkgMap = typesByPackage.getOrPut(file.packageName) { mutableMapOf() }
        if (pkgMap.containsKey(symbol.name)) {
            diagnostics.error(
                "重复声明类型 '${symbol.name}'（同包跨文件，S-5.7）",
                span.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        file.types[symbol.name] = symbol
        pkgMap[symbol.name] = symbol
    }

    private fun registerClassShell(file: FileScope, decl: YxClass, diagnostics: DiagnosticSink) {
        val symbol = YxClassSymbol(
            name = decl.name,
            typeParams = decl.typeParams.map { it.name },
            isData = false,
            isService = false,
            superType = null,
            interfaces = emptyList(),
            members = mutableListOf(),
            isAbstract = false,
            annotations = decl.annotations,
            visibility = decl.visibility,
            fileScope = file,
            span = decl.span,
        )
        registerType(file, symbol, decl.span, diagnostics)
    }

    private fun registerDataShell(file: FileScope, decl: YxDataClass, diagnostics: DiagnosticSink) {
        val symbol = YxClassSymbol(
            name = decl.name,
            typeParams = decl.typeParams.map { it.name },
            isData = true,
            isService = false,
            superType = null,
            interfaces = emptyList(),
            members = mutableListOf(),
            isAbstract = false,
            annotations = decl.annotations,
            visibility = decl.visibility,
            fileScope = file,
            span = decl.span,
        )
        registerType(file, symbol, decl.span, diagnostics)
    }

    private fun registerServiceShell(file: FileScope, decl: YxService, diagnostics: DiagnosticSink) {
        val symbol = YxClassSymbol(
            name = decl.name,
            typeParams = decl.typeParams.map { it.name },
            isData = false,
            isService = true,
            superType = null,
            interfaces = emptyList(),
            members = mutableListOf(),
            isAbstract = false,
            annotations = decl.annotations,
            fileScope = file,
            span = decl.span,
        )
        registerType(file, symbol, decl.span, diagnostics)
    }
}

object Builtins {
    private val BASIC = mapOf(
        "Int" to SemaType.INT,
        "Long" to SemaType.LONG,
        "Float" to SemaType.FLOAT,
        "Double" to SemaType.DOUBLE,
        "Boolean" to SemaType.BOOLEAN,
        "Char" to SemaType.CHAR,
        "String" to SemaType.STRING,
        "Byte" to SemaType.BYTE,
        "Any" to SemaType.ANY,
        "Unit" to SemaType.UnitT,
        "Nothing" to SemaType.NothingT,
        "Range" to SemaType.RANGE,
    )

    fun basicType(name: String): SemaType? = BASIC[name]

    /** 可直接按简单名从 JDK 类路径解析的工具类型（文档示例常用，01-§4.3/§8.5）。 */
    val BUILTIN_CLASSPATH = mapOf(
        "List" to "java.util.List",
        "Map" to "java.util.Map",
        "Set" to "java.util.Set",
        "Iterable" to "java.lang.Iterable",
        "System" to "java.lang.System",
        "Math" to "java.lang.Math",
        "StringBuilder" to "java.lang.StringBuilder",
        "Throwable" to "java.lang.Throwable",
        "Exception" to "java.lang.Exception",
        "RuntimeException" to "java.lang.RuntimeException",
        // yux-stdlib 静态工具类（T-M11-3）：`Text.uppercase("hello")` / `Colls.map(...)` 简单名可解析
        "Text" to "yux.core.Text",
        "Colls" to "yux.collection.Colls",
        "Tasks" to "yux.async.Tasks",
        "TestFramework" to "yux.test.TestFramework",
    )

    val typeParamNames = BASIC.keys
}
