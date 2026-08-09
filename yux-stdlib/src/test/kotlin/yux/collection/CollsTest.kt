package yux.collection

import org.junit.jupiter.api.Test
import yux.core.Text
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollsTest {

    /** 构造契约签名所需的 java.util.List 入参。 */
    @Suppress("UNCHECKED_CAST")
    private fun yxList(vararg items: Any?): java.util.List<Any?> {
        val result = java.util.ArrayList<Any?>()
        for (item in items) {
            result.add(item)
        }
        return result as java.util.List<Any?>
    }

    // ---------- map / filter / forEach ----------

    @Test
    fun `map doubles each number`() {
        val result = Colls.map(yxList(1, 2, 3)) { (it as Int) * 2 }
        assertEquals(listOf(2, 4, 6), result.toList())
    }

    @Test
    fun `map uppercases each string`() {
        val result = Colls.map(yxList("a", "b")) { (it as String).uppercase() }
        assertEquals(listOf("A", "B"), result.toList())
    }

    @Test
    fun `filter keeps only matching elements`() {
        val result = Colls.filter(yxList(1, 2, 3, 4)) { (it as Int) % 2 == 0 }
        assertEquals(listOf(2, 4), result.toList())
    }

    @Test
    fun `filter with strings keeps non-empty`() {
        val result = Colls.filter(yxList("yux", "", "lang")) { (it as String).isNotEmpty() }
        assertEquals(listOf("yux", "lang"), result.toList())
    }

    @Test
    fun `forEach visits every element in order`() {
        val visited = StringBuilder()
        Colls.forEach(yxList("a", "b", "c")) { visited.append(it) }
        assertEquals("abc", visited.toString())
    }

    // ---------- sort ----------

    @Test
    fun `sort descending by custom comparator`() {
        val result = Colls.sort(yxList(1, 3, 2, 5, 4)) { a, b -> (a as Int) > (b as Int) }
        assertEquals(listOf(5, 4, 3, 2, 1), result.toList())
    }

    @Test
    fun `sort strings by length`() {
        val result = Colls.sort(yxList("aaa", "b", "cc")) { a, b -> (a as String).length < (b as String).length }
        assertEquals(listOf("b", "cc", "aaa"), result.toList())
    }

    // ---------- sum ----------

    @Test
    fun `sum adds integers`() {
        assertEquals(6.0, Colls.sum(yxList(1, 2, 3)))
    }

    @Test
    fun `sum handles doubles`() {
        assertEquals(6.5, Colls.sum(yxList(1.5, 2.0, 3.0)))
    }

    @Test
    fun `sum treats non-numbers as zero`() {
        assertEquals(3.0, Colls.sum(yxList(1, "x", 2)))
    }

    @Test
    fun `sum of empty list is zero`() {
        assertEquals(0.0, Colls.sum(yxList()))
    }

    // ---------- max / first ----------

    @Test
    fun `max returns largest comparable element`() {
        assertEquals(5, Colls.max(yxList(1, 5, 3)))
    }

    @Test
    fun `max on strings uses natural order`() {
        assertEquals("c", Colls.max(yxList("a", "c", "b")))
    }

    @Test
    fun `max of empty list is null`() {
        assertNull(Colls.max(yxList()))
    }

    @Test
    fun `first returns head element`() {
        assertEquals(7, Colls.first(yxList(7, 8, 9)))
    }

    @Test
    fun `first of empty list is null`() {
        assertNull(Colls.first(yxList()))
    }

    // ---------- reduce ----------

    @Test
    fun `reduce folds sum`() {
        val result = Colls.reduce(yxList(1, 2, 3, 4)) { acc, x -> (acc as Int) + (x as Int) }
        assertEquals(10, result)
    }

    @Test
    fun `reduce folds string concatenation`() {
        val result = Colls.reduce(yxList("a", "b", "c")) { acc, x -> "$acc$x" }
        assertEquals("abc", result)
    }

    @Test
    fun `reduce of single element returns it`() {
        val result = Colls.reduce(yxList(42)) { _, _ -> 0 }
        assertEquals(42, result)
    }

    @Test
    fun `reduce of empty list is null`() {
        assertNull(Colls.reduce(yxList()) { _, _ -> null })
    }

    // ---------- yux.core.Text ----------

    @Test
    fun `text uppercase converts to upper`() {
        assertEquals("YUX LANG", Text.uppercase("yux Lang"))
    }

    @Test
    fun `text lowercase converts to lower`() {
        assertEquals("yux lang", Text.lowercase("YUX Lang"))
    }

    @Test
    fun `text split uses literal separator`() {
        assertEquals(listOf("a", "b", "c"), Text.split("a.b.c", ".").toList())
    }

    @Test
    fun `text split treats separator as plain text not regex`() {
        assertEquals(listOf("x", "y", "z"), Text.split("x|y|z", "|").toList())
    }

    @Test
    fun `text format replaces placeholders`() {
        val result = Text.format("%s has %d items", yxList("yux", 3))
        assertEquals("yux has 3 items", result)
    }

    @Test
    fun `text format with float placeholder`() {
        val result = Text.format("pi ~ %.2f", yxList(3.14159))
        assertEquals("pi ~ 3.14", result)
    }

    // ---------- 函数式风格：新列表 / 原列表不变 ----------

    @Test
    fun `ops return new lists and leave original untouched`() {
        val original = yxList(3, 1, 2)
        val mapped = Colls.map(original) { it }
        val filtered = Colls.filter(original) { true }
        val sorted = Colls.sort(original) { a, b -> (a as Int) > (b as Int) }
        Colls.forEach(original) { }
        Colls.reduce(original) { acc, _ -> acc }
        Colls.sum(original)
        Colls.max(original)
        Colls.first(original)

        assertEquals(listOf(3, 1, 2), original.toList())
        assertTrue(mapped !== original)
        assertTrue(filtered !== original)
        assertTrue(sorted !== original)
    }
}
