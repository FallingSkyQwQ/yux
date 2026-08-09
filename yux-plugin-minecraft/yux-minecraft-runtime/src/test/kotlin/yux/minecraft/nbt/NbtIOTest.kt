package yux.minecraft.nbt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 覆盖 03-§6.2 全标量映射表的数据类（含 Boolean ↔ NbtByte）。 */
data class AllTypes(
    val count: Int = 0,
    val flag: Byte = 0,
    val big: Long = 0L,
    val ratio: Float = 0f,
    val precise: Double = 0.0,
    val name: String = "",
    val active: Boolean = false,
)

/** 嵌套 data 类。 */
data class Position(val x: Int = 0, val z: Int = 0)

/** 含嵌套 data 类字段的复合。 */
data class OuterData(val title: String = "", val pos: Position = Position())

/** 含 List 字段的复合。 */
data class ListData(val labels: List<String> = emptyList(), val nums: List<Int> = emptyList())

/** 含 Map 字段的复合。 */
data class MapData(val scores: Map<String, Int> = emptyMap())

/** 用于缺失字段零值回退测试（默认值 7 在 v0.1 不被反射构造器启用）。 */
data class MissingData(val present: Int = 0, val absent: Int = 7)

/**
 * T-M8-9 NBT 序列化单元测试：全类型 round-trip 与 Boolean↔NbtByte 映射。
 */
class NbtIOTest {

    @Test
    fun `round-trip all scalar types`() {
        val original = AllTypes(
            count = 3,
            flag = 1,
            big = 1_000_000_000L,
            ratio = 1.5f,
            precise = 2.25,
            name = "steve",
            active = true,
        )
        val back = NbtIO.deserializeNbt(NbtIO.serializeNbt(original), AllTypes::class.java) as AllTypes
        assertEquals(original, back)
    }

    @Test
    fun `nested data class round-trip`() {
        val original = OuterData(title = "home", pos = Position(x = 10, z = 20))
        val back = NbtIO.deserializeNbt(NbtIO.serializeNbt(original), OuterData::class.java) as OuterData
        assertEquals(original, back)
    }

    @Test
    fun `list round-trip`() {
        val original = ListData(labels = listOf("a", "b"), nums = listOf(1, 2, 3))
        val back = NbtIO.deserializeNbt(NbtIO.serializeNbt(original), ListData::class.java) as ListData
        assertEquals(original, back)
    }

    @Test
    fun `map round-trip`() {
        val original = MapData(scores = mapOf("a" to 1, "b" to 2))
        val back = NbtIO.deserializeNbt(NbtIO.serializeNbt(original), MapData::class.java) as MapData
        assertEquals(original, back)
    }

    @Test
    fun `boolean maps to NbtByte 1 and 0`() {
        val on = NbtIO.serializeNbt(AllTypes(active = true))
        val off = NbtIO.serializeNbt(AllTypes(active = false))
        assertEquals(1, (on.entries.getValue("active") as NbtByte).value.toInt())
        assertEquals(0, (off.entries.getValue("active") as NbtByte).value.toInt())
    }

    @Test
    fun `missing field falls back to zero value`() {
        val tag = NbtCompound(linkedMapOf("present" to NbtInt(5)))
        val back = NbtIO.deserializeNbt(tag, MissingData::class.java) as MissingData
        assertEquals(5, back.present)
        assertEquals(0, back.absent)
    }
}
