package yux.minecraft.util

import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack

/**
 * 物品构建器（T-M8-11，03-§7.3）。
 *
 * 链式 API：`ItemBuilder(Material.DIAMOND_SWORD).name(...).lore([...]).enchant(...).build()`。
 *
 * v0.1 说明：build() 尝试应用 itemMeta（需要运行中的服务器）；无服务器环境下
 * （单元测试/离线）itemMeta 不可用时降级为裸 ItemStack，链式字段仍被保留。
 */
class ItemBuilder(private val material: Material) {

    private var displayName: String? = null
    private var loreLines: List<String> = emptyList()
    private val enchantments = linkedMapOf<Enchantment, Int>()

    /** 设置显示名。 */
    fun name(text: String): ItemBuilder {
        displayName = text
        return this
    }

    /** 设置 lore 行。 */
    fun lore(lines: List<String>): ItemBuilder {
        loreLines = lines
        return this
    }

    /** 追加附魔（无视等级上限）。 */
    fun enchant(enchantment: Enchantment, level: Int): ItemBuilder {
        enchantments[enchantment] = level
        return this
    }

    /** 构建物品；无服务器时 meta 应用失败则返回裸 ItemStack（v0.1 降级）。 */
    fun build(): ItemStack {
        val stack = ItemStack(material)
        val meta = try {
            stack.itemMeta ?: return stack
        } catch (e: RuntimeException) {
            return stack
        }
        displayName?.let { meta.setDisplayName(it) }
        if (loreLines.isNotEmpty()) meta.setLore(loreLines)
        for ((enchantment, level) in enchantments) {
            meta.addEnchant(enchantment, level, true)
        }
        stack.itemMeta = meta
        return stack
    }
}
