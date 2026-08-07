package yux.compiler.lexer

/** 单个记号：位置 + 种类 + 原始文本 + 前导 Trivia。 */
data class Token(
    val position: SourcePosition,
    val kind: TokenKind,
    val text: String,
    val leadingTrivia: List<Trivia> = emptyList(),
) {
    /** 稳定的文本表示，供 golden 快照与 `yuxc lex` 使用。 */
    fun pretty(): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "${position.line}:${position.column} ${kind.name} \"$escaped\""
    }
}

/** 记号流打印器。 */
object TokenPrinter {
    fun print(tokens: List<Token>): String = tokens.joinToString("\n") { it.pretty() }
}
