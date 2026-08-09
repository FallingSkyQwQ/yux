package yux.minecraft

import org.bukkit.Material
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import yux.minecraft.util.ItemBuilder
import yux.minecraft.util.LocationUtil
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** T-M8-11：ItemBuilder 链式构建与 LocationUtil（03-§7.3）。 */
class ItemBuilderTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupServer() {
            // Material/ItemStack 静态初始化需要 Bukkit.server（Paper API 前置条件）
            BukkitTestFixtures.installServer()
        }
    }

    @Test
    fun `链式方法返回同一构建器`() {
        val builder = ItemBuilder(Material.DIAMOND_SWORD)
        assertSame(builder, builder.name("§6传说之刃"))
        assertSame(builder, builder.lore(listOf("上古遗物")))
    }

    @Test
    fun `LocationUtil 解析坐标`() {
        val loc = LocationUtil.parse("0, 64, 0")
        assertEquals(0.0, loc?.x)
        assertEquals(64.0, loc?.y)
        assertEquals(0.0, loc?.z)

        val yawLoc = LocationUtil.parse("1,2,3,90.0,45.0")
        assertEquals(90.0f, yawLoc?.yaw)
        assertEquals(45.0f, yawLoc?.pitch)
    }

    @Test
    fun `LocationUtil 非法输入返回 null`() {
        assertNull(LocationUtil.parse(""))
        assertNull(LocationUtil.parse("1,2"))
        assertNull(LocationUtil.parse("1,2,3,4,5,6"))
        assertNull(LocationUtil.parse("a,b,c"))
    }
}
