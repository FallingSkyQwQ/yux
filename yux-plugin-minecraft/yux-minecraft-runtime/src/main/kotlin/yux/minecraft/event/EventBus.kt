package yux.minecraft.event

import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/**
 * 事件监听注册（T-M8-3 运行时侧，03-§4.1/§10）。
 *
 * 下沉产物（`event E(...)` 生成的 `E_Handler implements Listener`）经
 * [AnnotationScanner] 扫描后由本类注册到 Bukkit 事件总线。
 */
object EventBus {

    /** 注册监听器：`plugin.server.pluginManager.registerEvents(listener, plugin)`。 */
    fun register(plugin: Plugin, listener: Listener) {
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }
}
