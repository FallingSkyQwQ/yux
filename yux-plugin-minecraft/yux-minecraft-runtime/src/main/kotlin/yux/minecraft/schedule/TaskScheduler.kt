package yux.minecraft.schedule

import org.bukkit.plugin.Plugin
import yux.minecraft.annotations.YuxTask
import java.lang.reflect.InvocationTargetException

/**
 * 任务调度（T-M8-10，03-§9.2/§10）。
 *
 * 反射适配：读取任务类上的 [YuxTask]（delay/interval/async）并反射调用 `run()` 方法，
 * 委托给 BukkitScheduler（同步/异步 × 延迟/定时 四象限）。
 *
 * 健壮性约定：
 * - 负 delay/interval 视为编程错误，抛 [IllegalArgumentException]，不静默调度。
 * - 同一实例重复 [register] 会重复调度（调用方负责去重；Bukkit 为每次注册分配独立任务）。
 * - `run()` 抛出的异常包装为 [RuntimeException]（保留原因为 cause）后由 BukkitScheduler
 *   捕获记录，不中断调度器线程（任务级失败不影响其他任务）。
 */
object TaskScheduler {

    /** 注册调度任务；无 @YuxTask 注解时静默返回；负 delay/interval 抛 [IllegalArgumentException]。 */
    fun register(plugin: Plugin, task: Any) {
        val meta = task.javaClass.getAnnotation(YuxTask::class.java) ?: return
        require(meta.delay >= 0) { "YuxTask delay 不能为负: ${meta.delay}（类 ${task.javaClass.name}）" }
        require(meta.interval >= 0) { "YuxTask interval 不能为负: ${meta.interval}（类 ${task.javaClass.name}）" }
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
