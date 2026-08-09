package yux.minecraft

import org.bukkit.Server
import org.bukkit.UnsafeValues
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import java.io.File
import java.lang.reflect.Proxy
import java.net.URL
import java.util.logging.Logger

/** 捕获的调度任务（TaskSchedulerTest / PluginBootstrapTest 共用）。 */
data class CapturedTask(
    val delay: Long,
    val interval: Long,
    val async: Boolean,
    val runnable: Runnable,
)

/**
 * 测试用 Bukkit 对象工厂：接口一律用 java.lang.reflect.Proxy 模拟（无服务器依赖）。
 * Paper 部分类（Material/Enchantment/Registry）静态初始化需要 Bukkit.server，
 * 见 [installServer]。
 */
object BukkitTestFixtures {

    /** 假插件：dataFolder/server/logger/getName 有值，其余方法抛 UnsupportedOperationException。 */
    fun plugin(
        dataFolder: File = File(System.getProperty("java.io.tmpdir")),
        server: Server? = null,
    ): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getDataFolder" -> dataFolder
            "getServer" -> server ?: throw UnsupportedOperationException("stub: getServer")
            "getLogger" -> Logger.getLogger("TestPlugin")
            "getName" -> "test-plugin"
            else -> throw UnsupportedOperationException("stub: ${method.name}")
        }
    } as Plugin

    /** 假服务器：pluginManager/scheduler/unsafe/registry 有值，其余抛 UnsupportedOperationException。 */
    fun server(
        pluginManager: PluginManager? = null,
        scheduler: BukkitScheduler? = null,
    ): Server = Proxy.newProxyInstance(
        Server::class.java.classLoader,
        arrayOf(Server::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPluginManager" -> pluginManager ?: throw UnsupportedOperationException("stub: getPluginManager")
            "getScheduler" -> scheduler ?: throw UnsupportedOperationException("stub: getScheduler")
            "getUnsafe" -> unsafeValues()
            "getRegistry" -> null
            "getLogger" -> Logger.getLogger("FakeServer")
            else -> throw UnsupportedOperationException("stub: ${method.name}")
        }
    } as Server

    /** 满足静态初始化所需的最小 UnsafeValues（JavaPlugin 构造/Registry 初始化）。 */
    private fun unsafeValues(): UnsafeValues = Proxy.newProxyInstance(
        UnsafeValues::class.java.classLoader,
        arrayOf(UnsafeValues::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "createPluginLifecycleEventManager" -> null
            "isLegacy" -> false
            "getDataVersion" -> 0
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as UnsafeValues

    /**
     * 安装静态 Bukkit.server（Paper 静态初始化前置条件）。
     * 在 @BeforeAll 中调用一次即可；返回的 Server 带真实 scheduler/pluginManager 捕获器。
     */
    fun installServer(): Triple<Server, MutableList<Pair<Listener, Plugin>>, MutableList<CapturedTask>> {
        val (pm, listeners) = capturingPluginManager()
        val (scheduler, tasks) = capturingScheduler()
        val fake = server(pm, scheduler)
        // 反射设置 Bukkit.server（setServer 会走 ServerBuildInfo 读取服务器版本，纯 API 环境不可用）
        val field = org.bukkit.Bukkit::class.java.getDeclaredField("server")
        field.isAccessible = true
        field.set(null, fake)
        return Triple(fake, listeners, tasks)
    }

    /** 捕获 registerEvents(listener, plugin) 的假 PluginManager。 */
    fun capturingPluginManager(): Pair<PluginManager, MutableList<Pair<Listener, Plugin>>> {
        val captured = mutableListOf<Pair<Listener, Plugin>>()
        val pm = Proxy.newProxyInstance(
            PluginManager::class.java.classLoader,
            arrayOf(PluginManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "registerEvents" -> {
                    captured += (args[0] as Listener) to (args[1] as Plugin)
                    null
                }
                else -> throw UnsupportedOperationException("stub: ${method.name}")
            }
        }
        return (pm as PluginManager) to captured
    }

    /** 捕获调度调用的假 BukkitScheduler（返回 null 而非 Unit，避免 BukkitTask 强转失败）。 */
    fun capturingScheduler(): Pair<BukkitScheduler, MutableList<CapturedTask>> {
        val captured = mutableListOf<CapturedTask>()
        val scheduler = Proxy.newProxyInstance(
            BukkitScheduler::class.java.classLoader,
            arrayOf(BukkitScheduler::class.java),
        ) { _, method, args ->
            val runnable = args[1] as Runnable
            val delay = args[2] as Long
            val interval = if (args.size > 3) args[3] as Long else 0L
            when (method.name) {
                "runTaskLater" -> captured += CapturedTask(delay, 0L, false, runnable)
                "runTaskTimer" -> captured += CapturedTask(delay, interval, false, runnable)
                "runTaskLaterAsynchronously" -> captured += CapturedTask(delay, 0L, true, runnable)
                "runTaskTimerAsynchronously" -> captured += CapturedTask(delay, interval, true, runnable)
                else -> throw UnsupportedOperationException("stub: ${method.name}")
            }
            null
        }
        return (scheduler as BukkitScheduler) to captured
    }

    /** 测试类目录 URL（AnnotationScanner.scanClasses 用）；取测试类自身位置。 */
    fun testClassesDirUrl(): URL =
        BukkitTestFixtures::class.java.protectionDomain?.codeSource?.location ?: throw IllegalStateException()
}
