package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals

class NumberLiteralTest {

    private fun tokens(text: String) = Lexer(SourceFile("t.yux", text)).tokenize()

    @Test
    fun `decimal integer`() {
        val t = tokens("18")
        assertEquals(TokenKind.INT_LITERAL, t[0].kind)
        assertEquals("18", t[0].text)
    }

    @Test
    fun `underscore separator is kept raw with L suffix`() {
        val t = tokens("1_000L")
        assertEquals(TokenKind.INT_LITERAL, t[0].kind)
        assertEquals("1_000L", t[0].text)
    }

    @Test
    fun `hex literal`() {
        val t = tokens("0x1F")
        assertEquals(TokenKind.INT_LITERAL, t[0].kind)
        assertEquals("0x1F", t[0].text)
    }

    @Test
    fun `binary literal`() {
        val t = tokens("0b1010")
        assertEquals(TokenKind.INT_LITERAL, t[0].kind)
        assertEquals("0b1010", t[0].text)
    }

    @Test
    fun `hex with underscore`() {
        assertEquals("0xFF_FF", tokens("0xFF_FF")[0].text)
    }

    @Test
    fun `float with F suffix`() {
        val t = tokens("3.14F")
        assertEquals(TokenKind.FLOAT_LITERAL, t[0].kind)
        assertEquals("3.14F", t[0].text)
    }

    @Test
    fun `float defaults to double`() {
        assertEquals(TokenKind.FLOAT_LITERAL, tokens("3.14")[0].kind)
    }

    @Test
    fun `integer with F suffix is a float literal`() {
        assertEquals(TokenKind.FLOAT_LITERAL, tokens("1F")[0].kind)
    }

    @Test
    fun `exponent literals`() {
        assertEquals(TokenKind.FLOAT_LITERAL, tokens("1e5")[0].kind)
        assertEquals(TokenKind.FLOAT_LITERAL, tokens("1E-3")[0].kind)
        assertEquals(TokenKind.FLOAT_LITERAL, tokens("2.5e+2")[0].kind)
    }

    @Test
    fun `leading dot fraction`() {
        assertEquals(TokenKind.FLOAT_LITERAL, tokens(".5")[0].kind)
    }

    @Test
    fun `range is not a float fraction`() {
        val t = tokens("1..2")
        assertEquals(
            listOf(TokenKind.INT_LITERAL, TokenKind.RANGE, TokenKind.INT_LITERAL, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `member access after integer`() {
        val t = tokens("1.foo")
        assertEquals(TokenKind.INT_LITERAL, t[0].kind)
        assertEquals(TokenKind.DOT, t[1].kind)
    }

    @Test
    fun `zero is an integer`() {
        assertEquals(TokenKind.INT_LITERAL, tokens("0")[0].kind)
    }

    @Test
    fun `trailing digits after identifier stay separate`() {
        val t = tokens("123abc")
        assertEquals(listOf(TokenKind.INT_LITERAL, TokenKind.IDENTIFIER), t.dropLast(1).map { it.kind })
    }
}
