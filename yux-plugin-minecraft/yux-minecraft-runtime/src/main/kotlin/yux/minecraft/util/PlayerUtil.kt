package yux.minecraft.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 玩家工具（T-M8-11，03-§7.3）：按 UUID 字符串取在线玩家；非法 UUID 返回 null。
 */
object PlayerUtil {

    /** 在线玩家查询；UUID 格式非法或玩家不在线 → null。 */
    fun player(uuid: String): Player? {
        val parsed = try {
            UUID.fromString(uuid)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Bukkit.getPlayer(parsed)
    }
}
