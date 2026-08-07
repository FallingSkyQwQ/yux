package yux.compiler.lexer

/** 记号前导 Trivia：空白、注释、文档注释（02-§4.1）。 */
sealed interface Trivia {
    val text: String

    /** 水平空白（空格/制表/回车），不含换行。 */
    data class Whitespace(override val text: String) : Trivia

    /** 行注释 `// ...`。 */
    data class LineComment(override val text: String) : Trivia

    /** 块注释（可嵌套）。 */
    data class BlockComment(override val text: String) : Trivia

    /** 文档注释（生成文档用，语义同块注释）。 */
    data class DocComment(override val text: String) : Trivia
}
