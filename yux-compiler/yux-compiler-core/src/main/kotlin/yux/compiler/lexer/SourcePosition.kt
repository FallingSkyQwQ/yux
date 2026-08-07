package yux.compiler.lexer

/** 源码位置：1 起始的 line/column + 0 起始的字符偏移。 */
data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$line:$column"
}
