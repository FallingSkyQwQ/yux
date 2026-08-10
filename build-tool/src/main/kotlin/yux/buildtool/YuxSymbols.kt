package yux.buildtool

import yux.compiler.ast.YxClass
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxPackage
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxService
import java.nio.file.Path

/**
 * IDE 映射产物生成器（T-M7-7，05-§8）：把解析产物转换为稳定的 `yux-symbols.json` 文本，
 * 供 IDE 插件 / 编译映射使用。格式确定性、无第三方 JSON 依赖。
 */
object YuxSymbols {

    /**
     * 从解析产物生成 `yux-symbols.json` 文本。
     *
     * 每个源文件输出一个条目，按相对项目根路径（正斜杠）排序；[declsByFile] 的键可以是
     * 绝对路径或相对路径——绝对路径相对 [projectDir] 规约，相对路径直接采用。
     * 符号仅收录顶层声明：function/class/data/service/property（跳过 package/import/扩展/init）。
     */
    fun build(projectDir: Path, config: BuildConfig, declsByFile: Map<String, List<YxDecl>>): String {
        val files = declsByFile.entries
            .sortedBy { relativePath(projectDir, it.key) }
            .map { (path, decls) -> renderFile(relativePath(projectDir, path), decls) }
        return buildString {
            appendLine("{")
            appendLine("  \"project\": ${quote(config.name)},")
            appendLine("  \"version\": ${quote(config.version)},")
            appendLine("  \"files\": [")
            files.forEachIndexed { i, file ->
                if (i > 0) appendLine("    },")
                append(file)
            }
            if (files.isNotEmpty()) appendLine("    }")
            appendLine("  ]")
            appendLine("}")
        }
    }

    /** 单个文件条目主体（不含收尾 `}`，逗号由调用方管理）。 */
    private fun renderFile(relPath: String, decls: List<YxDecl>): String = buildString {
        appendLine("    {")
        appendLine("      \"file\": ${quote(relPath)},")
        appendLine("      \"class\": ${quote(fileClass(relPath, decls))},")
        appendLine("      \"symbols\": [")
        decls.mapNotNull(::symbol).forEachIndexed { i, s ->
            if (i > 0) appendLine(",")
            append("        { \"name\": ${quote(s.name)}, \"kind\": ${quote(s.kind)} }")
        }
        appendLine()
        appendLine("      ]")
    }

    /** 顶层声明 → 符号；package/import/扩展/init 不进入映射。 */
    private fun symbol(decl: YxDecl): Symbol? = when (decl) {
        is YxFunction -> Symbol(decl.name, "function")
        is YxClass -> Symbol(decl.name, "class")
        is YxDataClass -> Symbol(decl.name, "data")
        is YxService -> Symbol(decl.name, "service")
        is YxProperty -> if (decl.isTopLevel) Symbol(decl.name, "property") else null
        else -> null
    }

    private data class Symbol(val name: String, val kind: String)

    /** 相对项目根的路径（正斜杠）；键已是相对路径时直接采用。 */
    private fun relativePath(projectDir: Path, key: String): String {
        val path = Path.of(key)
        val rel = if (path.isAbsolute) projectDir.relativize(path) else path
        return rel.toString().replace('\\', '/')
    }

    /**
     * 文件类限定名：`main.yux` → `Main`；声明 `package com.example` 的 `main.yux` →
     * `com.example.Main`（与 IRGen 文件类命名一致，05-§5.3）。
     */
    private fun fileClass(relPath: String, decls: List<YxDecl>): String {
        val stem = relPath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val simple = stem.replaceFirstChar { it.uppercaseChar() }
        val pkg = decls.filterIsInstance<YxPackage>().firstOrNull()?.name ?: ""
        return if (pkg.isEmpty()) simple else "$pkg.$simple"
    }

    /** JSON 字符串转义（防御式：标识符仅需处理 `\` 与 `"`）。 */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
