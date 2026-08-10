package yux.minecraft.bootstrap

import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import yux.minecraft.annotations.YuxCommand
import yux.minecraft.annotations.YuxConfig
import yux.minecraft.annotations.YuxEvent
import yux.minecraft.annotations.YuxTask
import yux.minecraft.command.CommandRegistry
import yux.minecraft.config.ConfigManager
import yux.minecraft.event.EventBus
import yux.minecraft.schedule.TaskScheduler
import java.io.File
import java.net.URL
import java.util.jar.JarFile

/**
 * SDK 注解扫描器（T-M8-7，03-§4.2）。
 *
 * 扫描插件 jar/classes 目录（以 [JavaPlugin] 的 codeSource 为根），对带
 * `@YuxEvent/@YuxCommand/@YuxConfig/@YuxTask` 的类实例化并分派注册。
 * 该机制使语言扩展层的下沉产物（普通注解类）无需生成注册代码即可工作。
 *
 * 扫描范围限定在插件主类所在包及其子包（shaded jar 中的 kotlin-stdlib/snakeyaml
 * 等第三方类不参与加载）；主类位于默认包时退化为只扫默认包。
 *
 * 扫描与分派逻辑拆为 [scanClasses]/[dispatch] 内部函数，便于无服务器环境单测。
 */
object AnnotationScanner {

    private val SDK_ANNOTATIONS = listOf(
        YuxEvent::class.java,
        YuxCommand::class.java,
        YuxConfig::class.java,
        YuxTask::class.java,
    )

    /** 第三方包前缀：即使与主类包前缀巧合也跳过（防御性，正常插件主类不可能在此）。 */
    private val THIRD_PARTY_PREFIXES = listOf(
        "java.", "javax.", "kotlin.", "org.bukkit.", "org.yaml.", "org.apache.",
    )

    /** 扫描并分派注册全部带 SDK 注解的类。 */
    fun registerAll(plugin: JavaPlugin) {
        for (clazz in scan(plugin)) {
            dispatch(clazz, plugin)
        }
    }

    /** 扫描并返回带 SDK 注解的类（供测试/调试）。 */
    fun scan(plugin: JavaPlugin): List<Class<*>> {
        val location = plugin.javaClass.protectionDomain?.codeSource?.location ?: return emptyList()
        return scanClasses(location, plugin.javaClass.classLoader, plugin.javaClass.packageName)
    }

    /** 从 classpath 根扫描带 SDK 注解的类（jar 或目录），仅限 [mainPackage] 包内。 */
    internal fun scanClasses(location: URL, classLoader: ClassLoader, mainPackage: String): List<Class<*>> {
        val file = File(location.toURI())
        val names = if (file.isDirectory) {
            file.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(file).path.removeSuffix(".class").replace(File.separatorChar, '/').replace('/', '.') }
                .toList()
        } else {
            JarFile(file).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.contains("module-info") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
        return names
            .filter { inScope(it, mainPackage) }
            .mapNotNull { name ->
                try {
                    Class.forName(name, false, classLoader)
                } catch (e: Throwable) {
                    null
                }
            }
            .filter { clazz -> SDK_ANNOTATIONS.any { clazz.isAnnotationPresent(it) } }
    }

    /** 类名是否在扫描范围内：第三方前缀外，且与主类同包或在其子包。 */
    private fun inScope(className: String, mainPackage: String): Boolean {
        if (THIRD_PARTY_PREFIXES.any { className.startsWith(it) }) return false
        val pkg = className.substringBeforeLast('.', "")
        if (mainPackage.isEmpty()) return pkg.isEmpty()
        return pkg == mainPackage || pkg.startsWith("$mainPackage.")
    }

    /** 按注解分派注册单个类（供测试直接驱动）。 */
    internal fun dispatch(clazz: Class<*>, plugin: Plugin) {
        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: ReflectiveOperationException) {
            plugin.logger?.warning("实例化 SDK 注解类失败: ${clazz.name}: ${e.message}")
            return
        }
        when {
            clazz.isAnnotationPresent(YuxEvent::class.java) -> registerEvent(plugin, instance)
            clazz.isAnnotationPresent(YuxCommand::class.java) -> CommandRegistry.register(plugin, instance)
            clazz.isAnnotationPresent(YuxConfig::class.java) -> ConfigManager.load(plugin, instance)
            clazz.isAnnotationPresent(YuxTask::class.java) -> TaskScheduler.register(plugin, instance)
        }
    }

    private fun registerEvent(plugin: Plugin, instance: Any) {
        if (instance !is Listener) {
            plugin.logger?.warning("@YuxEvent 类必须实现 Listener: ${instance.javaClass.name}")
            return
        }
        EventBus.register(plugin, instance)
    }
}
