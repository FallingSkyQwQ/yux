package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.diag.Severity
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {

    private fun lex(text: String) = Lexer(SourceFile("t.yux", text))

    @Test
    fun `illegal character reports error and continues`() {
        val l = lex("a # b")
        val t = l.tokenize()
        assertEquals(
            listOf(TokenKind.IDENTIFIER, TokenKind.IDENTIFIER, TokenKind.EOF),
            t.map { it.kind },
        )
        assertTrue(l.diagnostics.hasErrors)
        assertEquals("Unexpected character '#'", l.diagnostics.diagnostics.first().message)
    }

    @Test
    fun `unterminated string reports error`() {
        val l = lex("\"abc")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
        assertTrue(l.diagnostics.diagnostics.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `unterminated block comment reports error`() {
        val l = lex("/* never closed")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
    }

    @Test
    fun `unterminated interpolation reports error`() {
        val l = lex("\"\${a")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
    }

    @Test
    fun `missing hex digit reports error`() {
        val l = lex("0x")
        l.tokenize()
        assertTrue(l.diagnostics.hasErrors)
    }

    @Test
    fun `bad input never throws and collects diagnostics`() {
        val l = lex("#!@\$'oops \"dangling")
        l.tokenize()
        assertTrue(l.diagnostics.diagnostics.isNotEmpty())
    }

    @Test
    fun `error positions are reported`() {
        val l = lex("a\n#")
        l.tokenize()
        val diag = l.diagnostics.diagnostics.first()
        assertEquals(2, diag.position?.line)
        assertEquals(1, diag.position?.column)
    }
}
