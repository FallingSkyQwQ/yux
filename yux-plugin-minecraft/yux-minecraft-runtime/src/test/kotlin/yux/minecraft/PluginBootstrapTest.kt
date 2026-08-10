package yux.minecraft

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import yux.minecraft.annotations.YuxCommand
import yux.minecraft.annotations.YuxConfig
import yux.minecraft.annotations.YuxEvent
import yux.minecraft.annotations.YuxTask
import yux.minecraft.bootstrap.AnnotationScanner
import yux.minecraft.config.ConfigManager
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-M8-7：AnnotationScanner 扫描与分派（03-§4.2，无服务器环境下驱动 scanClasses/dispatch）。 */
class PluginBootstrapTest {

    @YuxEvent(target = "PlayerJoinEvent")
    class TestListener : Listener {
        @EventHandler
        fun onJoin(e: PlayerJoinEvent) = Unit
    }

    @YuxCommand(path = "/ping")
    class TestCommand {
        fun execute(sender: org.bukkit.command.CommandSender, args: List<*>): Boolean = true
    }

    @YuxTask(delay = 5, interval = 20)
    class TestTask {
        fun run() = Unit
    }

    @YuxConfig(name = "TestConfig")
    data class TestConfig(val motd: String = "hello")

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupServer() {
            BukkitTestFixtures.installServer()
        }
    }

    @Test
    fun `scanClasses 发现全部带 SDK 注解的测试类`() {
        val scanned = AnnotationScanner.scanClasses(
            BukkitTestFixtures.testClassesDirUrl(),
            javaClass.classLoader,
            PluginBootstrapTest::class.java.packageName,
        )
        assertTrue(scanned.any { it == TestListener::class.java }, "应扫描到 @YuxEvent 监听器")
        assertTrue(scanned.any { it == TestCommand::class.java }, "应扫描到 @YuxCommand 命令")
        assertTrue(scanned.any { it == TestTask::class.java }, "应扫描到 @YuxTask 任务")
        assertTrue(scanned.any { it == TestConfig::class.java }, "应扫描到 @YuxConfig 配置")
        assertTrue(scanned.all { it.name.startsWith("yux.minecraft.") }, "只应扫描主类包内类: ${scanned.map { it.name }}")
    }

    @Test
    fun `scanClasses 跳过第三方前缀条目且不触发加载`() {
        val root = Files.createTempDirectory("scan-scope").toFile()
        try {
            copyResource(javaClass.classLoader, "yux/minecraft/PluginBootstrapTest\$TestConfig.class", root)
            copyResource(javaClass.classLoader, "kotlin/Unit.class", root)
            copyResource(javaClass.classLoader, "org/bukkit/event/Listener.class", root)
            copyResource(javaClass.classLoader, "org/yaml/snakeyaml/Yaml.class", root)
            val counting = object : ClassLoader(javaClass.classLoader) {
                val loaded = mutableListOf<String>()
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    loaded += name
                    return super.loadClass(name, resolve)
                }
            }
            val scanned = AnnotationScanner.scanClasses(
                root.toURI().toURL(),
                counting,
                PluginBootstrapTest::class.java.packageName,
            )
            assertEquals(listOf(TestConfig::class.java), scanned, "只应扫描到主类包内的注解类")
            assertTrue(
                counting.loaded.none { it.startsWith("kotlin.") || it.startsWith("org.bukkit.") || it.startsWith("org.yaml.") },
                "第三方前缀条目不应被加载: ${counting.loaded}",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /** 从 classpath 复制 .class 资源到合成扫描目录（模拟含第三方条目的 jar 结构）。 */
    private fun copyResource(classLoader: ClassLoader, resource: String, root: File) {
        val bytes = classLoader.getResourceAsStream(resource)?.readBytes() ?: error("资源缺失: $resource")
        val out = File(root, resource)
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
    }

    @Test
    fun `dispatch 按注解分派事件任务与配置`() {
        val (server, registeredListeners, capturedTasks) = BukkitTestFixtures.installServer()
        val plugin = BukkitTestFixtures.plugin(server = server)

        AnnotationScanner.dispatch(TestListener::class.java, plugin)
        AnnotationScanner.dispatch(TestTask::class.java, plugin)
        AnnotationScanner.dispatch(TestConfig::class.java, plugin)
        AnnotationScanner.dispatch(TestCommand::class.java, plugin) // 非 JavaPlugin → 命令路径静默跳过

        assertTrue(registeredListeners.any { it.first is TestListener }, "事件监听器应被注册")
        assertTrue(capturedTasks.any { it.delay == 5L && it.interval == 20L }, "repeat 任务应被注册")
        assertNotNull(ConfigManager.get("TestConfig"), "配置应被加载并缓存")
        assertEquals("hello", (ConfigManager.get("TestConfig") as? TestConfig)?.motd)
    }
}
