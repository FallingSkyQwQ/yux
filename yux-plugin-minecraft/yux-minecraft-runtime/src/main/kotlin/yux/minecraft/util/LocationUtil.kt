package yux.minecraft.util

import org.bukkit.Location

/**
 * 坐标工具（T-M8-11，03-§7.3）：解析 `"x,y,z"` / `"x,y,z,yaw,pitch"` 文本。
 * world 由调用方设置；格式非法返回 null。
 */
object LocationUtil {

    /** 解析坐标字符串；world 为 null（调用方赋值）。 */
    fun parse(text: String): Location? {
        val parts = text.split(',').map { it.trim() }
        if (parts.size < 3 || parts.size == 4 || parts.size > 5) return null
        return try {
            val x = parts[0].toDouble()
            val y = parts[1].toDouble()
            val z = parts[2].toDouble()
            if (parts.size == 5) {
                Location(null, x, y, z, parts[3].toFloat(), parts[4].toFloat())
            } else {
                Location(null, x, y, z)
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
