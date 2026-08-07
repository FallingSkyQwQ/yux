package yux.compiler.diag

import yux.compiler.lexer.SourcePosition

/** 诊断级别（02-§12.2）。 */
enum class Severity {
    ERROR,
    WARNING,
    REMIND,
}

/** 单条诊断。`code` 为诊断码（T-M3-10 的 ErrorCodes.md 唯一事实源），M1 阶段暂为空。 */
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val position: SourcePosition? = null,
    val code: String? = null,
)

/** 诊断收集器：编译期间累积，不抛异常。 */
class DiagnosticSink {
    private val _diagnostics = mutableListOf<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = _diagnostics
    val hasErrors: Boolean get() = _diagnostics.any { it.severity == Severity.ERROR }

    fun error(message: String, position: SourcePosition? = null, code: String? = null) {
        _diagnostics += Diagnostic(Severity.ERROR, message, position, code)
    }

    fun warning(message: String, position: SourcePosition? = null, code: String? = null) {
        _diagnostics += Diagnostic(Severity.WARNING, message, position, code)
    }

    fun remind(message: String, position: SourcePosition? = null, code: String? = null) {
        _diagnostics += Diagnostic(Severity.REMIND, message, position, code)
    }

    fun report(diagnostic: Diagnostic) {
        _diagnostics += diagnostic
    }
}
