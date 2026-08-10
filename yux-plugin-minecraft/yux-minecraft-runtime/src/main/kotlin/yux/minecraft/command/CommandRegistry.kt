package yux.minecraft.command

import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import yux.minecraft.annotations.YuxCommand
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * 命令注册（T-M8-4 运行时侧，03-§3.4/§10）。
 *
 * 反射适配：读取命令类上的 [YuxCommand] 注解（path/permission/aliases），并把
 * `execute(sender: CommandSender, args: List): Boolean` / `tab(sender, args): List`
 * 方法适配为 Bukkit 的 [CommandExecutor] / [TabCompleter]。
 *
 * 设计偏差：paper-api 1.21 已将 `getCommand(String)` 从 Plugin 接口移除（仅存在于
 * JavaPlugin），故 [register] 内部以安全向下转型取命令；[executorFor]/[tabCompleterFor]
 * 为纯反射适配器（无服务器依赖，可单测）。
 */
object CommandRegistry {

    /** 方法查找缓存键：同类同名方法只反射一次。 */
    private data class MethodKey(val clazz: Class<*>, val name: String)

    /** 方法查找缓存（computeIfAbsent 原子填充；Optional 表示“无此方法”的负缓存）。 */
    private val methodCache = ConcurrentHashMap<MethodKey, Optional<Method>>()

    /** 注册命令；无 @YuxCommand 注解、路径未在 plugin.yml 注册时静默返回。 */
    fun register(plugin: Plugin, command: Any) {
        val meta = command.javaClass.getAnnotation(YuxCommand::class.java) ?: return
        val bukkitCmd = (plugin as? JavaPlugin)?.getCommand(meta.path) ?: return
        bukkitCmd.setPermission(meta.permission.ifEmpty { null })
        bukkitCmd.setAliases(meta.aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        bukkitCmd.setExecutor(executorFor(command))
        tabCompleterFor(command)?.let { bukkitCmd.setTabCompleter(it) }
    }

    /** `execute(sender, List)` 方法 → CommandExecutor 适配器（无服务器依赖）。 */
    internal fun executorFor(command: Any): CommandExecutor? {
        val method = methodOrNull(command.javaClass, "execute") ?: return null
        return CommandExecutor { sender, _, _, args ->
            invoke(method, command, sender, args) as Boolean
        }
    }

    /** `tab(sender, List)` 方法 → TabCompleter 适配器；无 tab 方法返回 null。 */
    internal fun tabCompleterFor(command: Any): TabCompleter? {
        val method = methodOrNull(command.javaClass, "tab") ?: return null
        return TabCompleter { sender, _, _, args ->
            val result = invoke(method, command, sender, args)
            if (result is List<*>) result.mapNotNull { it as? String } else emptyList()
        }
    }

    private fun invoke(method: Method, command: Any, sender: CommandSender, args: Array<out String>): Any? =
        try {
            method.invoke(command, sender, Arrays.asList(*args))
        } catch (e: InvocationTargetException) {
            throw RuntimeException("命令 ${method.name} 抛出异常", e.targetException)
        }

    private fun methodOrNull(clazz: Class<*>, name: String): Method? =
        methodCache.computeIfAbsent(MethodKey(clazz, name)) { key ->
            Optional.ofNullable(
                try {
                    key.clazz.getMethod(key.name, CommandSender::class.java, List::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                },
            )
        }.orElse(null)
}
