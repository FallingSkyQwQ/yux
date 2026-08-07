package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringLiteralTest {

    private fun lex(text: String) = Lexer(SourceFile("t.yux", text))

    @Test
    fun `plain string is a single STRING_LITERAL token`() {
        val t = lex("\"Steve\"").tokenize()
        assertEquals(
            listOf(TokenKind.STRING_LITERAL, TokenKind.EOF),
            t.map { it.kind },
        )
        assertEquals("\"Steve\"", t[0].text)
    }

    @Test
    fun `escape sequences are preserved raw`() {
        val src = "\"\\n\\t\\u0041\""
        assertEquals(src, lex(src).tokenize()[0].text)
    }

    @Test
    fun `escaped dollar is not interpolation`() {
        val src = "\"\\\$name\""
        val t = lex(src).tokenize()
        assertEquals(TokenKind.STRING_LITERAL, t[0].kind)
        assertEquals(src, t[0].text)
    }

    @Test
    fun `char literal`() {
        val t = lex("'a'").tokenize()
        assertEquals(TokenKind.CHAR_LITERAL, t[0].kind)
        assertEquals("'a'", t[0].text)
    }

    @Test
    fun `char literal with escaped quote`() {
        val src = "'it\\'s'"
        assertEquals(src, lex(src).tokenize()[0].text)
    }

    @Test
    fun `raw string ignores escapes and interpolation`() {
        val src = "\"\"\"\\n 原样输出 \$不解析\"\"\""
        val t = lex(src).tokenize()
        assertEquals(
            listOf(TokenKind.RAW_STRING_LITERAL, TokenKind.EOF),
            t.map { it.kind },
        )
        assertEquals(src, t[0].text)
    }

    @Test
    fun `unterminated string reports error but still produces a token stream`() {
        val l = lex("\"abc")
        val t = l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
        assertEquals(TokenKind.STRING_START, t[0].kind)
    }

    @Test
    fun `unterminated raw string reports error`() {
        val l = lex("\"\"\"abc")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
    }

    @Test
    fun `unterminated char literal reports error`() {
        val l = lex("'a")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
    }

    @Test
    fun `string followed by newline terminates statement`() {
        val t = lex("\"a\"\nb").tokenize()
        assertEquals(
            listOf(
                TokenKind.STRING_LITERAL,
                TokenKind.NEWLINE,
                TokenKind.IDENTIFIER,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
    }
}
