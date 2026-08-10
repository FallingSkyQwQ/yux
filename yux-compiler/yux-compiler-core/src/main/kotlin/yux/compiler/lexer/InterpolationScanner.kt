package yux.compiler.lexer

/**
 * 字符串与插值分词（02-§4.3）。
 *
 * - `$name`：`INTERPOLATION_START` + `IDENTIFIER`；
 * - `${expr}`：进入括号平衡的表达式模式，表达式结束恢复字符串扫描（嵌套字符串递归）；
 * - `\$` 为字面 `$`；`"""` 原始串不解析插值/转义（01-§2.5）。
 */
class InterpolationScanner(private val lexer: Lexer) {

    fun scanString(): List<Token> {
        val openPos = lexer.pos()
        val stringStartOffset = lexer.offset
        lexer.advance() // "
        val tokens = mutableListOf<Token>()
        tokens += Token(openPos, TokenKind.STRING_START, "\"")

        var interpolated = false
        val sb = StringBuilder()
        var segmentPos = openPos

        while (true) {
            if (lexer.eof()) {
                lexer.diagnostics.error("Unterminated string literal", openPos)
                flushText(tokens, sb, segmentPos)
                return tokens
            }
            when (lexer.peek()) {
                '\\' -> {
                    if (sb.isEmpty()) segmentPos = lexer.pos()
                    val escPos = lexer.pos()
                    sb.append(lexer.advance())
                    when {
                        lexer.eof() -> Unit // 尾部反斜杠：字符串未闭合诊断在循环外处理
                        lexer.peek() in lexer.legalEscapes -> sb.append(lexer.advance())
                        lexer.peek() == 'u' -> {
                            val hexOk = (1..4).all { lexer.isHexDigit(lexer.peek(it)) }
                            if (hexOk) {
                                repeat(5) { sb.append(lexer.advance()) }
                            } else {
                                lexer.diagnostics.error(
                                    "非法转义 '\\u': 需要 4 位十六进制数字（01-§2.5）",
                                    escPos,
                                )
                                sb.append(lexer.advance())
                            }
                        }
                        else -> {
                            val bad = lexer.peek()
                            lexer.diagnostics.error("非法转义 '\\$bad'（01-§2.5）", escPos)
                            sb.append(lexer.advance())
                        }
                    }
                }
                '$' -> {
                    interpolated = true
                    flushText(tokens, sb, segmentPos)
                    tokens += Token(lexer.pos(), TokenKind.INTERPOLATION_START, "$")
                    lexer.advance()
                    when {
                        lexer.peek() == '{' -> {
                            tokens += Token(lexer.pos(), TokenKind.LBRACE, "{")
                            lexer.advance()
                            scanExpressionBody(tokens)
                        }
                        lexer.isIdentifierStart(lexer.peek()) -> tokens += lexer.scanIdentifierOrKeyword()
                        else -> lexer.diagnostics.error(
                            "Invalid interpolation: expected identifier or '{'",
                            lexer.pos(),
                        )
                    }
                    segmentPos = lexer.pos()
                }
                '"' -> {
                    lexer.advance()
                    flushText(tokens, sb, segmentPos)
                    tokens += Token(lexer.pos(), TokenKind.STRING_END, "\"")
                    if (!interpolated) {
                        val raw = lexer.text.substring(stringStartOffset, lexer.offset)
                        return listOf(Token(openPos, TokenKind.STRING_LITERAL, raw))
                    }
                    return tokens
                }
                else -> {
                    if (sb.isEmpty()) segmentPos = lexer.pos()
                    sb.append(lexer.advance())
                }
            }
        }
    }

    private fun flushText(tokens: MutableList<Token>, sb: StringBuilder, segmentPos: SourcePosition) {
        if (sb.isNotEmpty()) {
            tokens += Token(segmentPos, TokenKind.STRING_TEXT, sb.toString())
            sb.clear()
        }
    }

    /** 扫描 `${` 内部表达式，直到匹配的 `}`（brace 深度归零）。 */
    private fun scanExpressionBody(tokens: MutableList<Token>) {
        var depth = 1
        while (true) {
            val (trivia, _) = lexer.scanTrivia(emitNewlineToken = false)
            if (lexer.eof()) {
                lexer.diagnostics.error("Unterminated interpolation expression", lexer.pos())
                return
            }
            val c = lexer.peek()
            when {
                c == '}' -> {
                    lexer.advance()
                    tokens += Token(lexer.pos(), TokenKind.RBRACE, "}", trivia)
                    depth--
                    if (depth == 0) return
                }
                c == '{' -> {
                    lexer.advance()
                    tokens += Token(lexer.pos(), TokenKind.LBRACE, "{", trivia)
                    depth++
                }
                else -> tokens += lexer.attachTrivia(lexer.scanOneRealToken(), trivia)
            }
        }
    }
}
