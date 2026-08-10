package yux.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import yux.core.function.Function0

/**
 * 并发运行时入口（S-8.4：Task / launch / parallel / sleep / await）。
 *
 * Yux 程序 `import yux.async.Tasks` 后静态调用本对象方法；
 * 全部基于 JDK 原生并发（CompletableFuture / ForkJoinPool / Thread），
 * 不依赖协程（T-M11-2）。
 */
object Tasks {

    /**
     * 启动异步任务并立即返回 [Task]（S-8.4.1 的运行时入口）。
     *
     * 设计依据：Yux 语言中 `async` 是关键字，与 S-8.4.1 的 `async { }` 对应函数
     * 命名为 `launch`（既定设计偏差）；块经 [CompletableFuture.runAsync] 提交到
     * ForkJoinPool.commonPool()，调用方不阻塞。
     *
     * @param block 异步执行体（Yux lambda 运行时实现的 `yux.core.function.Function0`）
     */
    @JvmStatic
    fun launch(block: Function0<Unit>): Task {
        @Suppress("UNCHECKED_CAST") // runAsync 返回 CompletableFuture<Void>，get() 结果为 null，与 Any? 兼容
        return Task(CompletableFuture.runAsync(block::invoke) as CompletableFuture<Any?>)
    }

    /**
     * 并行执行块并阻塞至完成（S-8.4.3：块内无数据依赖的调用提交到 ForkJoinPool，块尾隐式 `join`）。
     *
     * 设计依据：S-8.4.3 规定 `parallel { }` 块尾隐式 join，因此本方法使用
     * [ForkJoinPool.invoke] 提交并原地等待，返回时块内副作用必然已生效。
     *
     * @param block 并行执行体（`yux.core.function.Function0`）
     */
    @JvmStatic
    fun parallel(block: Function0<Unit>) {
        // S-8.4.3：ForkJoinTask.adapt 包装 Runnable 后 invoke（阻塞至完成，即块尾隐式 join）
        ForkJoinPool.commonPool().invoke(ForkJoinTask.adapt { block.invoke() })
    }

    /**
     * 并行执行多个块并阻塞至全部完成（S-8.4.3 `parallel { }` 语句级拆分运行时入口）。
     *
     * 编译器把 `parallel { }` 块内互相独立的顶层语句各编译为一个 [Function0]，
     * 一并提交到 ForkJoinPool 并发执行；本方法等待全部任务完成后返回（块尾隐式 join）。
     *
     * @param blocks 并行执行体列表（各语句的 lambda）
     */
    @JvmStatic
    fun parallelAll(blocks: List<Function0<Unit>>) {
        val tasks = blocks.map { ForkJoinTask.adapt { it.invoke() } }
        tasks.forEach { ForkJoinPool.commonPool().execute(it) }
        tasks.forEach { it.join() }
    }

    /**
     * 阻塞当前线程休眠指定毫秒数（S-8.4.4：调度器与 `java.util.concurrent` 互操作）。
     *
     * 设计依据：与 `parallel` 块内 `Thread.sleep` 语义一致；被中断时
     * 恢复中断标志后抛 [RuntimeException]，与 [Task.await] 的中断处理保持一致。
     *
     * @param ms 休眠毫秒数
     */
    @JvmStatic
    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    /**
     * 阻塞等待任务完成并返回结果（S-8.4.2 顶层 `await(x)` 的运行时入口）。
     *
     * 设计依据：直接委托 [Task.await]，统一异常解包与中断语义。
     *
     * @param task 待等待的任务
     */
    @JvmStatic
    fun await(task: Task): Any? = task.await()
}
