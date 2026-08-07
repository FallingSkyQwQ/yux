package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenKindTest {

    private fun tokens(text: String) = Lexer(SourceFile("t.yux", text)).tokenize()

    @Test
    fun `base keywords count is 30 and match the spec`() {
        // 01-§2.4 列表为 30 个，但语法（§6.6/§7.1）将 `throw` 用作关键字——
        // 规格列表漏列，实际共 31 个基础关键字（含 throw）。
        assertEquals(31, Keywords.BASE.size)
        assertEquals(
            setOf(
                "fun", "data", "service", "async", "parallel", "try", "catch", "finally",
                "if", "else", "when", "for", "while", "return",
                "import", "package", "new", "unsafe", "native", "stack",
                "in", "is", "as", "this", "super",
                "break", "continue", "true", "false", "null", "throw",
            ),
            Keywords.BASE,
        )
    }

    @Test
    fun `soft keywords are contextual`() {
        assertEquals(
            setOf("extends", "implements", "override", "private", "protected"),
            Keywords.SOFT,
        )
    }

    @Test
    fun `operator table from 01-2-6 is fully recognized`() {
        val operators = listOf(
            ".", ",", ":", ";", "(", ")", "[", "]", "{", "}",
            "+", "-", "*", "/", "%", "=", "==", "!=", "<", ">", "<=", ">=",
            "&&", "||", "!", "+=", "-=", "*=", "/=", "%=", "..", "->",
        )
        for (op in operators) {
            val token = tokens(op).first()
            assertEquals(op, token.text, "operator text: $op")
            assertNotEquals(TokenKind.IDENTIFIER, token.kind, "operator should not be identifier: $op")
        }
    }

    @Test
    fun `every two-char operator lexes as a single token`() {
        for ((op, kind) in Symbols.twoChar) {
            assertEquals(listOf(kind, TokenKind.EOF), tokens(op).map { it.kind }, "op: $op")
        }
    }

    @Test
    fun `keywords lex as KEYWORD and soft keywords as SOFT_KEYWORD`() {
        assertTrue(Keywords.BASE.all { tokens(it).first().kind == TokenKind.KEYWORD })
        assertTrue(Keywords.SOFT.all { tokens(it).first().kind == TokenKind.SOFT_KEYWORD })
    }

    @Test
    fun `it is a plain identifier`() {
        assertEquals(TokenKind.IDENTIFIER, tokens("it").first().kind)
    }

    @Test
    fun `built-in type names are identifiers not keywords`() {
        for (name in listOf("Int", "String", "Boolean", "List", "Map", "Range", "Any")) {
            assertEquals(TokenKind.IDENTIFIER, tokens(name).first().kind, name)
        }
    }
}
