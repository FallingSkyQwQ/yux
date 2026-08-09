package yux.minecraft.bootstrap

import org.bukkit.plugin.java.JavaPlugin

/**
 * YuxPlugin SDK 运行时入口基类（T-M8-7，03-§4.2/§9.1）。
 *
 * [onEnable] 保留注解扫描注册逻辑（[AnnotationScanner.registerAll]），随后调用用户钩子
 * [onYuxEnable]。
 *
 * 设计偏差（03-§9.1 表）：Yux 源码层 `onEnable {}` 钩子下沉为覆盖 [onYuxEnable] 而非
 * 直接覆盖 `JavaPlugin.onEnable()`——父类必须保留注册逻辑，否则注解扫描会被用户覆盖掉。
 */
open class PluginBootstrap : JavaPlugin() {

    override fun onEnable() {
        AnnotationScanner.registerAll(this)
        onYuxEnable()
    }

    override fun onDisable() {
        onYuxDisable()
    }

    /** 用户级启用钩子（Yux `onEnable {}` 的下沉目标）。 */
    open fun onYuxEnable() = Unit

    /** 用户级停用钩子（Yux `onDisable {}` 的下沉目标）。 */
    open fun onYuxDisable() = Unit
}
