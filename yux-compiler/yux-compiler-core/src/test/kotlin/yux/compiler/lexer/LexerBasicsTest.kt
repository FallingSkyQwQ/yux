package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals

class LexerBasicsTest {

    private fun tokens(text: String) = Lexer(SourceFile("t.yux", text)).tokenize()

    @Test
    fun `identifiers may contain letters digits and underscore`() {
        val t = tokens("hello world_2 _priv UpperCamel")
        assertEquals(
            listOf("hello", "world_2", "_priv", "UpperCamel"),
            t.dropLast(1).map { it.text },
        )
    }

    @Test
    fun `identifier positions are 1-based line and column`() {
        val t = tokens("a\nbb")
        assertEquals(SourcePosition(0, 1, 1), t[0].position)
        assertEquals(SourcePosition(2, 2, 1), t[1].position)
    }

    @Test
    fun `simple symbols`() {
        val t = tokens("a == b")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.EQ, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `symbol text is preserved`() {
        val t = tokens("a <= b")
        assertEquals("a", t[0].text)
        assertEquals("<=", t[1].text)
        assertEquals("b", t[2].text)
    }

    @Test
    fun `arrow and range are multi-char operators`() {
        assertEquals(TokenKind.ARROW, tokens("->").first().kind)
        assertEquals(TokenKind.RANGE, tokens("..").first().kind)
    }

    @Test
    fun `at is an annotation marker`() {
        val t = tokens("@Serializable")
        assertEquals(
            listOf(TokenKind.AT, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `question marks nullable type`() {
        val t = tokens("name:String?")
        assertEquals(
            listOf(
                TokenKind.IDENTIFIER,
                TokenKind.COLON,
                TokenKind.IDENTIFIER,
                TokenKind.QUESTION,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
    }

    @Test
    fun `unicode identifier letters are accepted`() {
        val t = tokens("你好 = 1")
        assertEquals("你好", t[0].text)
    }

    @Test
    fun `statement newline is a NEWLINE token`() {
        val t = tokens("a = x\nb = y")
        assertEquals(
            listOf(
                TokenKind.IDENTIFIER, TokenKind.ASSIGN, TokenKind.IDENTIFIER,
                TokenKind.NEWLINE,
                TokenKind.IDENTIFIER, TokenKind.ASSIGN, TokenKind.IDENTIFIER,
                TokenKind.EOF,
            ),
            t.map { it.kind },
        )
    }

    @Test
    fun `semicolon is a symbol`() {
        assertEquals(TokenKind.SEMICOLON, tokens(";").first().kind)
    }
}
