package yux.minecraft

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import yux.minecraft.annotations.YuxTask
import yux.minecraft.schedule.TaskScheduler
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-M8-10：TaskScheduler 反射调度（03-§9.2 四象限）。 */
class TaskSchedulerTest {

    private var counter = 0

    @YuxTask(delay = 5, interval = 20)
    inner class RepeatTask {
        fun run() { counter++ }
    }

    @YuxTask(delay = 100)
    inner class DelayOnlyTask {
        fun run() { counter++ }
    }

    @YuxTask(delay = 50, async = true)
    inner class AsyncTask {
        fun run() { counter++ }
    }

    private fun pluginWithScheduler(captured: MutableList<CapturedTask>): Plugin {
        val (scheduler, list) = BukkitTestFixtures.capturingScheduler()
        val server = BukkitTestFixtures.server(BukkitTestFixtures.capturingPluginManager().first, scheduler)
        return BukkitTestFixtures.plugin(server = server).also { captured.addAll(list) }
    }

    @Test
    fun `repeat 任务按 delay interval 同步注册并可执行`() {
        val (scheduler, captured) = BukkitTestFixtures.capturingScheduler()
        val server = BukkitTestFixtures.server(BukkitTestFixtures.capturingPluginManager().first, scheduler)
        val plugin: Plugin = BukkitTestFixtures.plugin(server = server)

        TaskScheduler.register(plugin, RepeatTask())

        assertEquals(1, captured.size)
        val task = captured.single()
        assertEquals(5, task.delay)
        assertEquals(20, task.interval)
        assertFalse(task.async)
        task.runnable.run()
        assertEquals(1, counter)
    }

    @Test
    fun `delay 任务 interval 为 0`() {
        val (scheduler, captured) = BukkitTestFixtures.capturingScheduler()
        val server = BukkitTestFixtures.server(BukkitTestFixtures.capturingPluginManager().first, scheduler)
        val plugin: Plugin = BukkitTestFixtures.plugin(server = server)

        TaskScheduler.register(plugin, DelayOnlyTask())

        val task = captured.single()
        assertEquals(100, task.delay)
        assertEquals(0, task.interval)
        assertFalse(task.async)
    }

    @Test
    fun `async 任务走异步调度`() {
        val (scheduler, captured) = BukkitTestFixtures.capturingScheduler()
        val server = BukkitTestFixtures.server(BukkitTestFixtures.capturingPluginManager().first, scheduler)
        val plugin: Plugin = BukkitTestFixtures.plugin(server = server)

        TaskScheduler.register(plugin, AsyncTask())

        val task = captured.single()
        assertEquals(50, task.delay)
        assertTrue(task.async)
    }

    @Test
    fun `无 YuxTask 注解的对象被忽略`() {
        val (scheduler, captured) = BukkitTestFixtures.capturingScheduler()
        val server = BukkitTestFixtures.server(BukkitTestFixtures.capturingPluginManager().first, scheduler)
        val plugin: Plugin = BukkitTestFixtures.plugin(server = server)

        TaskScheduler.register(plugin, "not-a-task")

        assertEquals(0, captured.size)
    }
}
