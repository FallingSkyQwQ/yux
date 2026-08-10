package yux.compiler.lexer

import yux.compiler.diag.DiagnosticSink
import yux.compiler.source.SourceFile

/**
 * Yux 词法分析器（02-§4）。
 *
 * - 逐字符扫描，产出 `Token(position, kind, text, leadingTrivia)`；
 * - Trivia（空白/注释/文档注释）保留在前导位置，换行折叠为单个 `NEWLINE` 记号；
 * - 非法字符/未闭合字面量 → 诊断（ERROR），不抛异常。
 */
class Lexer(
    val source: SourceFile,
    val diagnostics: DiagnosticSink = DiagnosticSink(),
) {
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

    // ── 低层原语（InterpolationScanner 复用） ────────────────────────────────

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
     * 收集空白与注释；`emitNewlineToken=true` 时将连续换行折叠为一个 `NEWLINE` 记号，
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
                peek() == '/' && peek(1) == '/' -> trivia += scanLineComment()
                peek() == '/' && peek(1) == '*' -> trivia += scanBlockComment()
                else -> return TriviaResult(trivia, sawNewline)
            }
        }
        return TriviaResult(trivia, sawNewline)
    }

    private fun scanLineComment(): Trivia {
        val start = offset
        advance()
        advance()
        while (!eof() && peek() != '\n') advance()
        return Trivia.LineComment(text.substring(start, offset))
    }

    private fun scanBlockComment(): Trivia {
        val start = offset
        advance()
        advance()
        val isDoc = text.length >= start + 3 && text[start + 2] == '*' && text.getOrNull(start + 3) != '/'
        var depth = 1
        while (!eof() && depth > 0) {
            when {
                peek() == '/' && peek(1) == '*' -> {
                    advance()
                    advance()
                    depth++
                }
                peek() == '*' && peek(1) == '/' -> {
                    advance()
                    advance()
                    depth--
                }
                else -> advance()
            }
        }
        if (depth > 0) diagnostics.error("Unterminated block comment", pos())
        val raw = text.substring(start, offset)
        return if (isDoc) Trivia.DocComment(raw) else Trivia.BlockComment(raw)
    }

    // ── 单个逻辑记号（标识符/数字/字符/字符串/符号） ────────────────────────

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
            if (!isHexDigit(peek())) diagnostics.error("Expect hex digit after '0x'", startPos)
            while (isHexDigit(peek()) || peek() == '_') advance()
            if (peek() == 'L' || peek() == 'l') advance()
            return Token(startPos, TokenKind.INT_LITERAL, text.substring(start, offset))
        }
        if (c == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            advance()
            advance()
            if (!isBinDigit(peek())) diagnostics.error("Expect binary digit after '0b'", startPos)
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

    internal fun scanChar(): Token {
        val start = offset
        val startPos = pos()
        advance() // '
        val content = StringBuilder()
        var closed = false
        while (!eof()) {
            val c = advance()
            if (c == '\\') {
                content.append(c)
                if (eof()) break
                content.append(advance())
            } else if (c == '\'') {
                closed = true
                break
            } else {
                content.append(c)
            }
        }
        if (!closed) {
            diagnostics.error("Unterminated character literal", startPos)
        } else {
            validateCharContent(content.toString(), startPos)
        }
        return Token(startPos, TokenKind.CHAR_LITERAL, text.substring(start, offset))
    }

    /** 合法转义字符表（01-§2.5 Escape；`u` 需另带恰好 4 位十六进制数字）。 */
    internal val legalEscapes: Set<Char> = setOf('n', 't', 'r', 'b', 'f', '\\', '\'', '"', '$', '0')

    /**
     * 校验字符字面量内容（01-§2.5 CharLiteral）：转义必须合法，且解码后恰好一个字符。
     * 违规仅报告诊断（ERROR），不抛异常；词法层错误恢复不中断。
     */
    private fun validateCharContent(content: String, pos: SourcePosition) {
        var count = 0
        var i = 0
        while (i < content.length) {
            if (content[i] == '\\') {
                val len = escapeLength(content, i)
                if (len < 0) {
                    if (content.getOrNull(i + 1) == 'u') {
                        diagnostics.error("非法转义 '\\u': 需要 4 位十六进制数字（01-§2.5）", pos)
                    } else {
                        val next = content.getOrNull(i + 1) ?: '\\'
                        diagnostics.error("非法转义 '\\$next'（01-§2.5）", pos)
                    }
                    i += if (content.getOrNull(i + 1) == 'u') 6 else 2
                } else {
                    i += len
                }
                count++
            } else {
                if (content[i] == '\n') diagnostics.error("字符字面量不能包含换行（01-§2.5）", pos)
                i++
                count++
            }
        }
        if (count != 1) {
            diagnostics.error("字符字面量必须恰好包含一个字符（01-§2.5）", pos)
        }
    }

    /**
     * 返回以 `content[i]`（`\`）起始的合法转义总长度（2 或 6）；不合法返回 -1。
     */
    internal fun escapeLength(content: String, i: Int): Int {
        val next = content.getOrNull(i + 1) ?: return -1
        if (next in legalEscapes) return 2
        if (next == 'u') {
            val hex = content.substring(i + 2, minOf(i + 6, content.length))
            return if (hex.length == 4 && hex.all(::isHexDigit)) 6 else -1
        }
        return -1
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
        if (!closed) diagnostics.error("Unterminated raw string literal", startPos)
        return Token(startPos, TokenKind.RAW_STRING_LITERAL, text.substring(start, offset))
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
        diagnostics.error("Unexpected character '$c'", startPos)
        advance()
        return null
    }

    internal fun isHexDigit(c: Char): Boolean =
        c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    private fun isBinDigit(c: Char): Boolean = c == '0' || c == '1'
}
