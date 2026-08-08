package yux.core

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreLibTest {

    private fun capture(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(prev)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    @Test
    fun `print writes without newline`() {
        val out = capture { CoreLib.print("Hello Yux") }
        assertEquals("Hello Yux", out)
    }

    @Test
    fun `println appends newline`() {
        val out = capture { CoreLib.println(42) }
        assertEquals("42\n", out)
    }

    @Test
    fun `print accepts null`() {
        val out = capture { CoreLib.print(null) }
        assertEquals("null", out)
    }
}

class RangeTest {

    @Test
    fun `range iterates inclusive endpoints`() {
        val values = Range(0, 3).toList()
        assertEquals(listOf(0, 1, 2, 3), values)
    }

    @Test
    fun `single element range yields one value`() {
        assertEquals(listOf(5), Range(5, 5).toList())
    }

    @Test
    fun `descending range is empty`() {
        assertTrue(Range(3, 1).toList().isEmpty())
    }

    @Test
    fun `next past end throws`() {
        val it = Range(1, 1).iterator()
        assertEquals(1, it.next())
        var threw = false
        try {
            it.next()
        } catch (_: NoSuchElementException) {
            threw = true
        }
        assertTrue(threw, "越界 next 应抛 NoSuchElementException")
    }

    @Test
    fun `iterator supports hasNext protocol`() {
        val it = Range(0, 2).iterator()
        assertTrue(it.hasNext())
        assertEquals(0, it.next())
        assertEquals(1, it.next())
        assertEquals(2, it.next())
        assertTrue(!it.hasNext())
    }
}

class FunctionNTest {

    @Test
    fun `function0 lambda works`() {
        val f: Function0<String> = { "hello" }
        assertEquals("hello", f.invoke())
    }

    @Test
    fun `function1 lambda with boxed arg`() {
        val f: Function1<Int, Int> = { it + 1 }
        assertEquals(42, f.invoke(41))
    }

    @Test
    fun `function2 and function3 combine`() {
        val add: Function2<Int, Int, Int> = { a, b -> a + b }
        assertEquals(3, add.invoke(1, 2))
        val join: Function3<String, String, String, String> = { a, b, c -> "$a$b$c" }
        assertEquals("abc", join.invoke("a", "b", "c"))
    }
}
