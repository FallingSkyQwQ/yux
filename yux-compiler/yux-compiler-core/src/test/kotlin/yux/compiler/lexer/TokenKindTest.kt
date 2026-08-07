package yux.compiler.lexer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenKindTest {

    @Test
    fun `base keywords count is 30 and match the spec`() {
        assertEquals(30, Keywords.BASE.size)
        assertEquals(
            setOf(
                "fun", "data", "service", "async", "parallel", "try", "catch", "finally",
                "if", "else", "when", "for", "while", "return",
                "import", "package", "new", "unsafe", "native", "stack",
                "in", "is", "as", "this", "super",
                "break", "continue", "true", "false", "null",
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
    fun `base and soft keyword tables are disjoint`() {
        assertTrue(Keywords.BASE.intersect(Keywords.SOFT).isEmpty())
    }

    @Test
    fun `every two-char operator from 01-2-6 is registered`() {
        val operators = listOf(
            "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "..", "->",
        )
        for (op in operators) {
            assertTrue(Symbols.twoChar.containsKey(op), "two-char operator: $op")
        }
    }

    @Test
    fun `every single-char operator from 01-2-6 is registered`() {
        val operators = listOf(
            '.', ',', ':', ';', '(', ')', '[', ']', '{', '}',
            '+', '-', '*', '/', '%', '=', '<', '>', '!',
        )
        for (op in operators) {
            assertTrue(Symbols.single.containsKey(op), "single-char operator: $op")
        }
    }

    @Test
    fun `annotation and nullable markers are registered as single symbols`() {
        assertEquals(TokenKind.AT, Symbols.single['@'])
        assertEquals(TokenKind.QUESTION, Symbols.single['?'])
    }

    @Test
    fun `two-char and single-char tables do not collide`() {
        assertTrue(Symbols.single.keys.none { it.toString() in Symbols.twoChar })
    }
}
