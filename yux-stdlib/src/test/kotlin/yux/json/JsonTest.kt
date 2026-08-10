package yux.json

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * yux.json.Json 序列化单元测试（S-8.3）：标量 / data 类嵌套 / List / Map 往返。
 */
class JsonTest {

    private data class Player(val name: String, val health: Int, val alive: Boolean)

    private data class Team(
        val leader: String,
        val score: Long,
        val ratio: Double,
        val tag: Char,
        val members: List<String>,
        val bonus: Map<String, String>,
    )

    private data class Nested(val outer: String, val inner: Player)

    // ── 标量 ─────────────────────────────────────────────────────────────────

    @Test
    fun `serialize scalars`() {
        assertEquals("null", Json.serialize(null))
        assertEquals("42", Json.serialize(42))
        assertEquals("7", Json.serialize(7L))
        assertEquals("1.5", Json.serialize(1.5))
        assertEquals("2.25", Json.serialize(2.25f))
        assertEquals("true", Json.serialize(true))
        assertEquals("\"a\"", Json.serialize('a'))
        assertEquals("\"hi\"", Json.serialize("hi"))
        assertEquals("3", Json.serialize(3.toByte()))
    }

    @Test
    fun `serialize string escapes`() {
        assertEquals("\"a\\\"b\"", Json.serialize("a\"b"))
        assertEquals("\"x\\ny\"", Json.serialize("x\ny"))
        assertEquals("\"a\\\\b\"", Json.serialize("a\\b"))
    }

    @Test
    fun `deserialize scalars by class marker`() {
        assertEquals(42, Json.deserialize("42", Int::class.java))
        assertEquals(7L, Json.deserialize("7", Long::class.java))
        assertEquals(1.5, Json.deserialize("1.5", Double::class.java))
        assertEquals(2.25f, Json.deserialize("2.25", Float::class.java))
        assertEquals(true, Json.deserialize("true", Boolean::class.java))
        assertEquals('a', Json.deserialize("\"a\"", Char::class.java))
        assertEquals("hi", Json.deserialize("\"hi\"", String::class.java))
        assertEquals(3.toByte(), Json.deserialize("3", Byte::class.java))
        assertNull(Json.deserialize("null", String::class.java))
    }

    // ── data 类 ──────────────────────────────────────────────────────────────

    @Test
    fun `data class round trip`() {
        val player = Player("Steve", 20, true)
        val json = Json.serialize(player)
        assertEquals("""{"name":"Steve","health":20,"alive":true}""", json)
        assertEquals(player, Json.deserialize(json, Player::class.java))
    }

    @Test
    fun `data class with list and map round trip`() {
        val team = Team("Alex", 100L, 0.5, 'x', listOf("a", "b"), mapOf("k" to "v1", "j" to "v2"))
        val json = Json.serialize(team)
        assertTrue(json.contains("\"members\":[\"a\",\"b\"]"), json)
        assertTrue(json.contains("\"bonus\":{\"k\":\"v1\",\"j\":\"v2\"}"), json)
        assertEquals(team, Json.deserialize(json, Team::class.java))
    }

    @Test
    fun `nested data class round trip`() {
        val nested = Nested("top", Player("Bob", 5, false))
        val json = Json.serialize(nested)
        assertTrue(json.contains("\"inner\":{\"name\":\"Bob\""), json)
        assertEquals(nested, Json.deserialize(json, Nested::class.java))
    }

    // ── List / Map 独立往返 ─────────────────────────────────────────────────

    @Test
    fun `list and map values round trip`() {
        assertEquals("[1,2,3]", Json.serialize(listOf(1, 2, 3)))
        // 裸 List/Map 元素类型运行时不可知（v0.1 无泛型 Signature）：整数按 Long 解析
        assertEquals(listOf(1L, 2L, 3L), Json.deserialize("[1,2,3]", java.util.List::class.java))
        assertEquals("""{"a":1}""", Json.serialize(mapOf("a" to 1)))
        assertEquals(mapOf("a" to 1L), Json.deserialize("""{"a":1}""", java.util.Map::class.java))
    }

    @Test
    fun `array round trip`() {
        assertEquals("[1,2]", Json.serialize(intArrayOf(1, 2)))
        assertEquals(listOf(1L, 2L), Json.deserialize("[1,2]", java.util.List::class.java))
    }

    // ── 解析容错 ─────────────────────────────────────────────────────────────

    @Test
    fun `deserialize rejects malformed json`() {
        assertFailsWith<IllegalArgumentException> { Json.deserialize("{", Player::class.java) }
        assertFailsWith<IllegalArgumentException> { Json.deserialize("[1,", java.util.List::class.java) }
        assertFailsWith<IllegalArgumentException> { Json.deserialize("""{"name":1}""", Player::class.java) }
        assertFailsWith<IllegalArgumentException> { Json.deserialize("""{"health":1}""", Player::class.java) }
    }

    @Test
    fun `deserialize rejects non class marker`() {
        assertFailsWith<IllegalArgumentException> { Json.deserialize("42", "Int") }
    }

    @Test
    fun `serialize unknown type fails with message`() {
        val e = assertFailsWith<IllegalArgumentException> { Json.serialize(Any()) }
        assertTrue(e.message!!.contains("不支持"), e.message)
    }

    @Test
    fun `round trip preserves nullable scalar field`() {
        val json = Json.serialize(Box(null))
        assertEquals("""{"value":null}""", json)
        val back = Json.deserialize(json, Box::class.java) as Box
        assertNotNull(back)
        assertNull(back.value)
    }

    private data class Box(val value: String?)
}
