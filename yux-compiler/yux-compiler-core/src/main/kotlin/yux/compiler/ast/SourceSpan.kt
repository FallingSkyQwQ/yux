package yux.compiler.ast

import yux.compiler.lexer.SourcePosition

/**
 * 源码范围（02-§5.2）：AST 节点携带的半开区间 [start, end)。
 *
 * `end` 指向节点最后一个字符之后的偏移；`line/column` 以 1 起始。
 */
data class SourceSpan(
    val start: SourcePosition,
    val end: SourcePosition,
) {
    override fun toString(): String = "$start..$end"
}
