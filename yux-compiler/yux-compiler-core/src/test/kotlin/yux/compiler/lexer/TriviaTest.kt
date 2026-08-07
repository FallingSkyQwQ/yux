package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriviaTest {

    private fun tokens(text: String) = Lexer(SourceFile("t.yux", text)).tokenize()

    @Test
    fun `nested block comment is one trivia`() {
        val t = tokens("a /* /* nested */ */ b")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
        val trivia = t[1].leadingTrivia
        assertTrue(trivia.any { it is Trivia.BlockComment && it.text.contains("nested") })
    }

    @Test
    fun `line comment is trivia on the NEWLINE token`() {
        val t = tokens("a // hi\nb")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.NEWLINE, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
        assertTrue(t[1].leadingTrivia.any { it is Trivia.LineComment })
    }

    @Test
    fun `consecutive newlines fold into a single NEWLINE`() {
        val t = tokens("a\n\n\nb")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.NEWLINE, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `blank lines between code still fold`() {
        val t = tokens("a\n// note\n\nb")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.NEWLINE, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `doc comment is preserved as DocComment trivia`() {
        val t = tokens("/** docs */ fun main(){}")
        assertEquals(TokenKind.KEYWORD, t[0].kind)
        assertTrue(t[0].leadingTrivia.any { it is Trivia.DocComment })
    }

    @Test
    fun `leading whitespace is trivia on first token`() {
        val t = tokens("    a")
        assertEquals("a", t[0].text)
        assertTrue(t[0].leadingTrivia.any { it is Trivia.Whitespace })
    }

    @Test
    fun `comment at end of file attaches to EOF`() {
        val t = tokens("a // trailing")
        assertEquals(TokenKind.IDENTIFIER, t[0].kind)
        assertEquals(TokenKind.EOF, t[1].kind)
        assertTrue(t[1].leadingTrivia.any { it is Trivia.LineComment })
    }

    @Test
    fun `block comment spanning lines does not emit NEWLINE`() {
        val t = tokens("a /* x\ny */ b")
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
    }

    @Test
    fun `empty doc comment is a block comment`() {
        val t = tokens("/**/ a")
        assertTrue(t[0].leadingTrivia.any { it is Trivia.BlockComment })
        assertTrue(t[0].leadingTrivia.none { it is Trivia.DocComment })
    }
}
