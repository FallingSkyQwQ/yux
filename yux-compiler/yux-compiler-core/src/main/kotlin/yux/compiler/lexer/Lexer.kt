package yux.compiler.lexer

import yux.compiler.source.SourceFile

/**
 * Yux 词法分析器（02-§4）——骨架 + 数字 + 字符/字符串 + 插值分词。
 *
 * - 逐字符扫描，产出 `Token(position, kind, text, leadingTrivia)`；
 * - 空白与换行折叠为单个 `NEWLINE` 记号；
 * - 字符/字符串转义与 `\$`、`"""` 原始串；
 * - `$name`/`${expr}` 插值分词（InterpolationScanner，括号平衡 + 嵌套字符串）；
 * - 非法字符跳过（错误诊断在 T-M1-7 接入）。
 */
class Lexer(val source: SourceFile) {
    internal val state = ScannerState(source.text)
    internal val text: String get() = state.text
    internal val offset: Int get() = state.offset
    private val interpolation = InterpolationScanner(this)

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            val produced = nextToken()
            if (produced.isEmpty()) continue
            tokens += produced
            if (tokens.last().kind == TokenKind.EOF) break
        }
        return tokens
    }

    // ── 顶层记号 ─────────────────────────────────────────────────────────────

    private fun nextToken(): List<Token> {
        val (trivia, sawNewline) = scanTrivia(emitNewlineToken = true)
        if (sawNewline) return listOf(Token(pos(), TokenKind.NEWLINE, "\n", trivia))
        if (eof()) return listOf(Token(pos(), TokenKind.EOF, "", trivia))
        return attachTrivia(scanOneRealToken(), trivia)
    }

    // ── 低层原语 ────────────────────────────────────────────────────────────

    internal fun eof(): Boolean = offset >= text.length

    internal fun peek(rel: Int = 0): Char =
        if (offset + rel < text.length) text[offset + rel] else '\u0000'

    internal fun advance(): Char {
        val c = text[offset]
        state.offset++
        if (c == '\n') {
            state.line++
            state.column = 1
        } else {
            state.column++
        }
        return c
    }

    internal fun pos(): SourcePosition = SourcePosition(offset, state.line, state.column)

    internal fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

    internal fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetter() || c.isDigit()

    internal fun attachTrivia(tokens: List<Token>, trivia: List<Trivia>): List<Token> =
        tokens.mapIndexed { i, t ->
            if (i == 0 && trivia.isNotEmpty()) t.copy(leadingTrivia = t.leadingTrivia + trivia) else t
        }

    // ── Trivia 与换行 ────────────────────────────────────────────────────────

    internal data class TriviaResult(val trivia: List<Trivia>, val sawNewline: Boolean)

    /**
     * 收集空白；`emitNewlineToken=true` 时将连续换行折叠为一个 `NEWLINE` 记号，
     * 否则换行并入 Trivia（插值表达式内部）。
     */
    internal fun scanTrivia(emitNewlineToken: Boolean): TriviaResult {
        val trivia = mutableListOf<Trivia>()
        var sawNewline = false
        while (!eof()) {
            when {
                peek() == ' ' || peek() == '\t' || peek() == '\r' -> {
                    val start = offset
                    while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) advance()
                    trivia += Trivia.Whitespace(text.substring(start, offset))
                }
                peek() == '\n' -> {
                    advance()
                    if (emitNewlineToken) sawNewline = true else trivia += Trivia.Whitespace("\n")
                }
                else -> return TriviaResult(trivia, sawNewline)
            }
        }
        return TriviaResult(trivia, sawNewline)
    }

    // ── 单个逻辑记号（标识符/数字/字符/字符串/符号） ─────────────────────────

    internal fun scanOneRealToken(): List<Token> {
        val c = peek()
        return when {
            isIdentifierStart(c) -> listOf(scanIdentifierOrKeyword())
            c.isDigit() -> listOf(scanNumber())
            c == '.' && peek(1).isDigit() -> listOf(scanNumber())
            c == '\'' -> listOf(scanChar())
            c == '"' -> {
                if (peek(1) == '"' && peek(2) == '"') listOf(scanRawString()) else interpolation.scanString()
            }
            else -> listOfNotNull(scanSymbol())
        }
    }

    internal fun scanIdentifierOrKeyword(): Token {
        val start = offset
        val startPos = pos()
        while (isIdentifierPart(peek())) advance()
        val word = text.substring(start, offset)
        val kind = when (word) {
            in Keywords.BASE -> TokenKind.KEYWORD
            in Keywords.SOFT -> TokenKind.SOFT_KEYWORD
            else -> TokenKind.IDENTIFIER
        }
        return Token(startPos, kind, word)
    }

    internal fun scanNumber(): Token {
        val start = offset
        val startPos = pos()
        val c = peek()

        if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance()
            advance()
            while (isHexDigit(peek()) || peek() == '_') advance()
            if (peek() == 'L' || peek() == 'l') advance()
            return Token(startPos, TokenKind.INT_LITERAL, text.substring(start, offset))
        }
        if (c == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            advance()
            advance()
            while (isBinDigit(peek()) || peek() == '_') advance()
            if (peek() == 'L' || peek() == 'l') advance()
            return Token(startPos, TokenKind.INT_LITERAL, text.substring(start, offset))
        }

        var isFloat = false
        while (peek().isDigit() || peek() == '_') advance()

        if (peek() == '.' && peek(1).isDigit()) {
            isFloat = true
            advance()
            while (peek().isDigit() || peek() == '_') advance()
        }

        if ((peek() == 'e' || peek() == 'E') && isExponentStart()) {
            isFloat = true
            advance()
            if (peek() == '+' || peek() == '-') advance()
            while (peek().isDigit()) advance()
        }

        val suffix = peek()
        if (suffix == 'L' || suffix == 'l' || suffix == 'F' || suffix == 'f' || suffix == 'D' || suffix == 'd') {
            advance()
            if (suffix == 'F' || suffix == 'f' || suffix == 'D' || suffix == 'd') isFloat = true
        }

        val kind = if (isFloat) TokenKind.FLOAT_LITERAL else TokenKind.INT_LITERAL
        return Token(startPos, kind, text.substring(start, offset))
    }

    private fun isExponentStart(): Boolean {
        val next = peek(1)
        if (next.isDigit()) return true
        if (next == '+' || next == '-') return peek(2).isDigit()
        return false
    }

    private fun scanRawString(): Token {
        val start = offset
        val startPos = pos()
        advance()
        advance()
        advance() // """
        var closed = false
        while (!eof()) {
            if (peek() == '"' && peek(1) == '"' && peek(2) == '"') {
                advance()
                advance()
                advance()
                closed = true
                break
            }
            advance()
        }
        return Token(startPos, TokenKind.RAW_STRING_LITERAL, text.substring(start, offset))
    }

    internal fun scanChar(): Token {
        val start = offset
        val startPos = pos()
        advance() // '
        var closed = false
        while (!eof()) {
            val c = advance()
            if (c == '\\') {
                if (!eof()) advance()
            } else if (c == '\'') {
                closed = true
                break
            }
        }
        return Token(startPos, TokenKind.CHAR_LITERAL, text.substring(start, offset))
    }

    internal fun scanSymbol(): Token? {
        val startPos = pos()
        if (offset + 1 < text.length) {
            val two = text.substring(offset, offset + 2)
            val kind = Symbols.twoChar[two]
            if (kind != null) {
                advance()
                advance()
                return Token(startPos, kind, two)
            }
        }
        val c = peek()
        val kind = Symbols.single[c]
        if (kind != null) {
            advance()
            return Token(startPos, kind, c.toString())
        }
        advance()
        return null
    }

    private fun isHexDigit(c: Char): Boolean =
        c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    private fun isBinDigit(c: Char): Boolean = c == '0' || c == '1'
}
