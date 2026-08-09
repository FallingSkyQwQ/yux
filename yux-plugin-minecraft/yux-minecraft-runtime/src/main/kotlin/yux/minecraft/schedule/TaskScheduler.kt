package yux.minecraft.schedule

import org.bukkit.plugin.Plugin
import yux.minecraft.annotations.YuxTask
import java.lang.reflect.InvocationTargetException

/**
 * 任务调度（T-M8-10，03-§9.2/§10）。
 *
 * 反射适配：读取任务类上的 [YuxTask]（delay/interval/async）并反射调用 `run()` 方法，
 * 委托给 BukkitScheduler（同步/异步 × 延迟/定时 四象限）。
 */
object TaskScheduler {

    /** 注册调度任务；无 @YuxTask 注解时静默返回。 */
    fun register(plugin: Plugin, task: Any) {
        val meta = task.javaClass.getAnnotation(YuxTask::class.java) ?: return
        val runnable = Runnable { invokeRun(task) }
        val scheduler = plugin.server.scheduler
        when {
            meta.async && meta.interval > 0L -> scheduler.runTaskTimerAsynchronously(plugin, runnable, meta.delay, meta.interval)
            meta.async -> scheduler.runTaskLaterAsynchronously(plugin, runnable, meta.delay)
            meta.interval > 0L -> scheduler.runTaskTimer(plugin, runnable, meta.delay, meta.interval)
            else -> scheduler.runTaskLater(plugin, runnable, meta.delay)
        }
    }

    private fun invokeRun(task: Any) {
        try {
            task.javaClass.getMethod("run").invoke(task)
        } catch (e: InvocationTargetException) {
            throw RuntimeException("任务 run() 抛出异常", e.targetException)
        }
    }
}
