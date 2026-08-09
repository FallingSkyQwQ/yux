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
        )
        assertTrue(scanned.any { it == TestListener::class.java }, "应扫描到 @YuxEvent 监听器")
        assertTrue(scanned.any { it == TestCommand::class.java }, "应扫描到 @YuxCommand 命令")
        assertTrue(scanned.any { it == TestTask::class.java }, "应扫描到 @YuxTask 任务")
        assertTrue(scanned.any { it == TestConfig::class.java }, "应扫描到 @YuxConfig 配置")
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
