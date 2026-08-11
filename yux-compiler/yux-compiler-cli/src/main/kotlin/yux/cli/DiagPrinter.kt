package yux.cli

import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.Severity

/**
 * 诊断渲染（M16，T-M16-4）：彩色严重级别 + 诊断码 + 位置 + 源码行与插入符标注。
 *
 * 格式（颜色关闭时与旧版逐字节兼容）：
 * ```
 * ERROR[E0004] 1:5: 类型不匹配
 *   1 | x: Int = "str"
 *     |     ^
 * ```
 */
object DiagPrinter {
    /** 输出到 stderr；[sourceText] 提供时附加源码标注。 */
    fun print(
        sink: DiagnosticSink,
        sourceText: String? = null,
        colors: Colors = Colors(false),
    ) {
        if (sink.diagnostics.isEmpty()) return
        System.err.println()
        System.err.println("-- 诊断 --")
        sink.diagnostics.forEach { System.err.print(render(it, sourceText, colors)) }
    }

    /** 渲染单条诊断。 */
    fun render(
        d: yux.compiler.diag.Diagnostic,
        sourceText: String?,
        colors: Colors,
    ): String {
        val sb = StringBuilder()
        val pos = d.position
        sb.append(severityLabel(d.severity, colors))
        d.code?.let { sb.append(colors.cyan("[$it]")) }
        pos?.let { sb.append(' ').append(it) }
        sb.append(": ").append(d.message).append('\n')
        if (sourceText != null && pos != null) {
            appendSourceSnippet(sb, sourceText, pos.line, pos.column, colors)
        }
        return sb.toString()
    }

    private fun severityLabel(
        severity: Severity,
        colors: Colors,
    ): String =
        when (severity) {
            Severity.ERROR -> colors.red(colors.bold("ERROR"))
            Severity.WARNING -> colors.yellow("WARNING")
            Severity.REMIND -> colors.cyan("REMIND")
        }

    /** 源码行 + 插入符标注。 */
    private fun appendSourceSnippet(
        sb: StringBuilder,
        source: String,
        line: Int,
        column: Int,
        colors: Colors,
    ) {
        val lines = source.lines()
        val lineText = lines.getOrNull(line - 1) ?: return
        val lineNo = line.toString()
        sb
            .append("  ")
            .append(lineNo)
            .append(" | ")
            .append(lineText)
            .append('\n')
        val pad = " ".repeat(lineNo.length)
        val caretCol = (column - 1).coerceAtLeast(0)
        sb
            .append("  ")
            .append(pad)
            .append(" | ")
            .append(" ".repeat(caretCol))
            .append(colors.red("^"))
            .append('\n')
    }
}
