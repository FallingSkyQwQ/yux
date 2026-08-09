package yux.minecraft.annotations

/**
 * 事件处理器注解（T-M8-1 / 03-§4.3 注解契约）。
 *
 * 语言扩展层把 `event PlayerJoin(player) { ... }` 语法下沉为：
 * ```
 * @YuxEvent(target = "PlayerJoinEvent")
 * class PlayerJoinEvent_Handler { fun handle(e: PlayerJoinEvent) { ... } }
 * ```
 * 运行时 `PluginBootstrap.onEnable()` 扫描本注解，把实例注册进 EventBus，
 * 并按其 [target] 监听对应的 Bukkit 事件类。
 *
 * @property target 事件类名（如 "PlayerJoinEvent"），运行时按名字解析 Bukkit 事件类。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YuxEvent(val target: String)

/**
 * 命令处理器注解（T-M8-1 / 03-§4.3 注解契约）。
 *
 * 语言扩展层把 `command "/home" { ... }` 语法下沉为：
 * ```
 * @YuxCommand(path = "/home", permission = "myserver.home", aliases = "h,homepage")
 * class HomeCommand {
 *     fun execute(sender: CommandSender, args: List<String>): Boolean { ... }
 *     fun tab(sender: CommandSender, args: List<String>): List<String> { ... }
 * }
 * ```
 * 运行时注册为 Bukkit `CommandExecutor`（含 `TabCompleter`）。
 *
 * @property path 命令路径（含 `/` 前缀）。
 * @property permission 执行所需权限节点，空串表示无权限要求。
 * @property aliases 逗号分隔的别名（v0.1 注解仅支持标量实参，别名以字符串聚合）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YuxCommand(
    val path: String,
    val permission: String = "",
    val aliases: String = "",
)

/**
 * 配置类注解（T-M8-1 / 03-§3.5、§4.3 注解契约）。
 *
 * 语言扩展层把 `config Server { motd = "..."; maxPlayers = 100 }` 下沉为带本注解的 data 类：
 * 字段默认值即配置文件默认项；运行时 ConfigManager 按 [path] 读取 YAML，
 * 用磁盘值覆盖默认字段，并按 [name] 缓存实例。
 *
 * @property name 缓存名（缺省为类简单名）。
 * @property path 相对插件数据目录的配置文件路径。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YuxConfig(val name: String = "", val path: String = "config.yml")

/**
 * 定时任务注解（T-M8-1 / 03-§3.6、§4.3 注解契约）。
 *
 * 语言扩展层把 `task repeat(interval:20) { }` / `task delay(100) { }` 下沉为带本注解的类；
 * 运行时按 [delay]/[interval] 交给 BukkitScheduler 调度（[async] 为 true 走异步线程）。
 *
 * @property delay 首次执行延迟（tick）。
 * @property interval 重复间隔（tick），为 0 表示仅执行一次。
 * @property async 是否在异步线程执行。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YuxTask(val delay: Long = 0, val interval: Long = 0, val async: Boolean = false)
