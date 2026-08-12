package yux.minecraft

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Test
import yux.minecraft.annotations.YuxCommand
import yux.minecraft.command.CommandRegistry
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** T-M8-4 运行时侧：CommandRegistry 反射适配器（03-§3.4，无服务器环境）。 */
class CommandRegistryTest {

    @YuxCommand(path = "/home", permission = "myserver.home", aliases = "h")
    inner class FakeYuxCommand {
        var invokedArgs: List<*>? = null
        fun execute(sender: CommandSender, args: List<*>): Boolean {
            invokedArgs = args
            return true
        }
    }

    /** 测试用最小 Command 子类（Command 为抽象类，onCommand 的 Command 参数需要实例）。 */
    private class FakeCommand : org.bukkit.command.Command("home") {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = true
    }

    private fun senderProxy(): CommandSender = Proxy.newProxyInstance(
        CommandSender::class.java.classLoader,
        arrayOf(CommandSender::class.java),
    ) { _, method, _ ->
        if (method.returnType == Boolean::class.javaPrimitiveType) true
        else throw UnsupportedOperationException("stub: ${method.name}")
    } as CommandSender

    @Test
    fun `executorFor 仅对实现 execute 的类产出适配器`() {
        val yuxCommand = FakeYuxCommand()
        assertNotNull(CommandRegistry.executorFor(yuxCommand), "FakeYuxCommand 有 execute → 应有适配器")

        val noExecute = object : Any() { val x = 1 }
        assertNull(CommandRegistry.executorFor(noExecute), "无 execute 方法 → null")
    }

    @Test
    fun `适配器调用 execute 传入 List String`() {
        val yuxCommand = FakeYuxCommand()
        val executor = CommandRegistry.executorFor(yuxCommand)
        assertEquals(true, executor?.onCommand(senderProxy(), FakeCommand(), "home", arrayOf("set")))
        assertEquals(listOf("set"), yuxCommand.invokedArgs)
    }

    @Test
    fun `tabCompleterFor 仅在有 tab 方法时产出`() {
        val yuxCommand = FakeYuxCommand()
        assertNull(CommandRegistry.tabCompleterFor(yuxCommand), "无 tab 方法 → null")

        val withTab = object : Any() {
            fun tab(sender: CommandSender, args: List<*>): List<String> = listOf("home", "set")
        }
        val completer = CommandRegistry.tabCompleterFor(withTab)
        assertEquals(listOf("home", "set"), completer?.onTabComplete(senderProxy(), FakeCommand(), "home", arrayOf()))
    }

    @Test
    fun `commandName 去掉斜杠前缀取末段`() {
        assertEquals("sethome", CommandRegistry.commandName("/sethome"))
        assertEquals("back", CommandRegistry.commandName("/admin/back"))
        assertEquals("delhome", CommandRegistry.commandName("delhome"))
    }

    @Test
    fun `register 对非 JavaPlugin 或未注册路径静默返回`() {
        val fakePlugin = BukkitTestFixtures.plugin(server = BukkitTestFixtures.server())
        CommandRegistry.register(fakePlugin, FakeYuxCommand()) // 非 JavaPlugin → 静默
        CommandRegistry.register(fakePlugin, "not-a-command") // 无注解 → 静默
        // 不抛异常即通过
    }

    @Test
    fun `重复创建适配器走方法缓存且执行正常`() {
        val yuxCommand = FakeYuxCommand()
        val first = CommandRegistry.executorFor(yuxCommand)
        assertEquals(true, first?.onCommand(senderProxy(), FakeCommand(), "home", arrayOf("set")))
        assertEquals(listOf("set"), yuxCommand.invokedArgs)

        val second = CommandRegistry.executorFor(yuxCommand)
        assertEquals(true, second?.onCommand(senderProxy(), FakeCommand(), "home", arrayOf("del")))
        assertEquals(listOf("del"), yuxCommand.invokedArgs)

        val withTab = object : Any() {
            fun tab(sender: CommandSender, args: List<*>): List<String> = listOf("a", "b")
        }
        val completer = CommandRegistry.tabCompleterFor(withTab)
        assertEquals(listOf("a", "b"), completer?.onTabComplete(senderProxy(), FakeCommand(), "home", arrayOf()))
    }
}
