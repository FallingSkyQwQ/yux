package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterpolationTest {

    private fun tokens(text: String) = Lexer(SourceFile("t.yux", text)).tokenize()

    @Test
    fun `simple name interpolation`() {
        val t = tokens("\"\$a\"")
        assertEquals(
            listOf(
                TokenKind.STRING_START,
                TokenKind.INTERPOLATION_START,
                TokenKind.IDENTIFIER,
                TokenKind.STRING_END,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
        assertEquals("a", t[2].text)
    }

    @Test
    fun `brace interpolation with expression`() {
        val t = tokens("\"\$a\${b + c}\"")
        assertEquals(
            listOf(
                TokenKind.STRING_START,
                TokenKind.INTERPOLATION_START, TokenKind.IDENTIFIER,
                TokenKind.INTERPOLATION_START, TokenKind.LBRACE,
                TokenKind.IDENTIFIER, TokenKind.PLUS, TokenKind.IDENTIFIER,
                TokenKind.RBRACE,
                TokenKind.STRING_END,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
    }

    @Test
    fun `string text segments are emitted`() {
        val t = tokens("\"Hi \$name, next=\"")
        val texts = t.filter { it.kind == TokenKind.STRING_TEXT }.map { it.text }
        assertEquals(listOf("Hi ", ", next="), texts)
    }

    @Test
    fun `nested string inside interpolation`() {
        val t = tokens("\"\${ f(\"x\") }\"")
        assertEquals(
            listOf(
                TokenKind.STRING_START,
                TokenKind.INTERPOLATION_START, TokenKind.LBRACE,
                TokenKind.IDENTIFIER, TokenKind.LPAREN,
                TokenKind.STRING_LITERAL,
                TokenKind.RPAREN, TokenKind.RBRACE,
                TokenKind.STRING_END,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
    }

    @Test
    fun `braces balance across nested interpolation`() {
        val t = tokens("\"\${ a(b(1)) }\$c\"")
        val kinds = t.map { it.kind }
        assertTrue(kinds.any { it == TokenKind.RBRACE })
        assertEquals(TokenKind.STRING_END, kinds[kinds.lastIndex - 1])
    }

    @Test
    fun `interpolated string produces no plain STRING_LITERAL token`() {
        val t = tokens("\"Hello \$name\"")
        assertEquals(0, t.count { it.kind == TokenKind.STRING_LITERAL })
    }

    @Test
    fun `raw string does not interpolate`() {
        val t = tokens("\"\"\"\$x\"\"\"")
        assertEquals(
            listOf(TokenKind.RAW_STRING_LITERAL, TokenKind.EOF),
            t.map { it.kind },
        )
    }
}
