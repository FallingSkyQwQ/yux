package yux.compiler.irgen

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 字面量解码防御层（词法器/sema 应已拦截畸形输入；此处验证解码器本身不崩溃、不截断）。
 */
class LiteralsTest {

    @Test
    fun `decodeInt never throws and does not truncate`() {
        assertEquals(0, Literals.decodeInt("99999999999999999999"))
        assertEquals(99999999999L, Literals.decodeInt("99999999999"))
        assertEquals(8589934591L, Literals.decodeInt("0x1FFFFFFFF"))
        assertEquals(Int.MAX_VALUE, Literals.decodeInt("2147483647"))
    }

    @Test
    fun `decodeChar falls back on malformed content`() {
        assertEquals('\u0000', Literals.decodeChar("''"))
        assertEquals('\u0000', Literals.decodeChar("'ab'"))
        assertEquals('a', Literals.decodeChar("'a'"))
    }

    @Test
    fun `decodeEscapes tolerates bad unicode escape`() {
        assertEquals("uZZZZ", Literals.decodeEscapes("\\uZZZZ"))
        assertEquals("A", Literals.decodeEscapes("\\u0041"))
    }
}
