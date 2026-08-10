package yux.serializer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * yux.serializer.YuxSerializer 序列化单元测试（S-8.3）：data 类 / 嵌套 / List<Data> /
 * Map<String, Data> / 可空 null / 枚举 / 循环引用报错 / 无字段类报错 往返。
 */
class SerializerTest {

    private data class Player(val name: String, val health: Int, val alive: Boolean)

    private data class Nested(val outer: String, val inner: Player)

    private data class Roster(val players: List<Player>)

    private data class Scoreboard(val scores: Map<String, Player>)

    private data class Profile(val name: String, val nickname: String?)

    private data class Matrix(val rows: List<List<Int>>)

    private data class MaybeRoster(val players: List<Player?>)

    private enum class Color { RED, GREEN, BLUE }

    private data class Palette(val name: String, val color: Color)

    /** 可变结构（非 data 类，字段仍被反射识别），用于构造自引用。 */
    private class LoopBox {
        var value: LoopBox? = null
    }

    /** 无字段类：serialize/deserialize 均应明确报错。 */
    private class FieldLess

    private fun <T : Any> roundTrip(x: T): T =
        YuxSerializer.deserialize(YuxSerializer.serialize(x), x.javaClass)

    // ── 基本往返 ─────────────────────────────────────────────────────────────

    @Test
    fun `flat data class round trip`() {
        val player = Player("Steve", 20, true)
        assertEquals("""{"name":"Steve","health":20,"alive":true}""", YuxSerializer.serialize(player))
        assertEquals(player, roundTrip(player))
    }

    @Test
    fun `nested data class round trip`() {
        val nested = Nested("top", Player("Bob", 5, false))
        assertTrue(YuxSerializer.serialize(nested).contains("\"inner\":{\"name\":\"Bob\""))
        assertEquals(nested, roundTrip(nested))
    }

    // ── 泛型元素类型转换（回归：List<Data> 必须还原为 Data 而非 Map）─────────────

    @Test
    fun `list of data class round trip with element conversion`() {
        val roster = Roster(listOf(Player("a", 1, true), Player("b", 2, false)))
        val back = roundTrip(roster)
        assertEquals(roster, back)
        assertTrue(back.players[0] is Player, "元素类型必须是 Player，而非 Map: ${back.players[0]!!::class}")
        assertTrue(back.players[1] is Player)
        assertEquals(1, back.players[0]!!.health)
    }

    @Test
    fun `map of data class round trip with value conversion`() {
        val board = Scoreboard(
            mapOf("first" to Player("a", 1, true), "second" to Player("b", 2, false)),
        )
        val back = roundTrip(board)
        assertEquals(board, back)
        assertTrue(back.scores["first"] is Player, "值类型必须是 Player，而非 Map")
    }

    // ── 可空 ─────────────────────────────────────────────────────────────────

    @Test
    fun `nullable field null round trip`() {
        val profile = Profile("Steve", null)
        assertEquals("""{"name":"Steve","nickname":null}""", YuxSerializer.serialize(profile))
        val back = roundTrip(profile)
        assertEquals("Steve", back.name)
        assertNotNull(back)
        assertNull(back.nickname)
    }

    @Test
    fun `nullable list element null round trip`() {
        val roster = MaybeRoster(listOf(Player("a", 1, true), null))
        val back = roundTrip(roster)
        assertEquals(roster, back)
        assertNull(back.players[1])
        assertTrue(back.players[0] is Player)
    }

    // ── 枚举 ─────────────────────────────────────────────────────────────────

    @Test
    fun `enum field round trip by constant name`() {
        val palette = Palette("war", Color.RED)
        assertTrue(YuxSerializer.serialize(palette).contains("\"color\":\"RED\""))
        assertEquals(palette, roundTrip(palette))
    }

    @Test
    fun `enum standalone round trip`() {
        assertEquals("\"GREEN\"", YuxSerializer.serialize(Color.GREEN))
        assertEquals(Color.GREEN, YuxSerializer.deserialize("\"GREEN\"", Color::class.java))
    }

    // ── 报错路径 ─────────────────────────────────────────────────────────────

    @Test
    fun `self referencing structure fails with clear cycle error`() {
        val box = LoopBox()
        box.value = box
        val e = assertFailsWith<IllegalArgumentException> { YuxSerializer.serialize(box) }
        assertTrue(e.message!!.contains("循环"), e.message)
    }

    @Test
    fun `fieldless class fails on both directions`() {
        val ser = assertFailsWith<IllegalArgumentException> { YuxSerializer.serialize(FieldLess()) }
        assertTrue(ser.message!!.contains("无字段"), ser.message)
        val de = assertFailsWith<IllegalArgumentException> {
            YuxSerializer.deserialize("{}", FieldLess::class.java)
        }
        assertTrue(de.message!!.contains("字段"), de.message)
    }

    // ── 嵌套泛型 ─────────────────────────────────────────────────────────────

    @Test
    fun `nested generic list of list round trip`() {
        val matrix = Matrix(listOf(listOf(1, 2), listOf(3)))
        assertEquals(matrix, roundTrip(matrix))
        assertTrue(roundTrip(matrix).rows[0][0] is Int)
    }
}
