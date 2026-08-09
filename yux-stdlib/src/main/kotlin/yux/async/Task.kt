package yux.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * 异步任务句柄（S-8.4.1：`launch` 的返回类型）。
 *
 * 包装 [CompletableFuture]：Yux 的 `Tasks.launch` 返回本类实例，
 * `await` 阻塞等待任务完成。全部基于 JDK 原生并发（T-M11-2），
 * 不依赖协程运行时。
 */
class Task internal constructor(internal val future: CompletableFuture<Any?>) {

    /**
     * 阻塞等待任务完成并返回结果（S-8.4.2：`await(x)` 挂起等待）。
     *
     * - 执行异常（[ExecutionException]）解包 cause 后抛 [RuntimeException]，
     *   使 Yux 层只感知到运行时异常；
     * - 等待期间被中断（[InterruptedException]）时恢复中断标志后抛 [RuntimeException]，
     *   保持线程中断语义。
     */
    fun await(): Any? {
        return try {
            future.get()
        } catch (e: ExecutionException) {
            // S-8.4.2：任务内异常经 Future 包装为 ExecutionException，解包 cause 抛出
            throw RuntimeException(e.cause)
        } catch (e: InterruptedException) {
            // 恢复中断标志后抛出，调用方可通过 isInterrupted() 感知
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    /** 取消任务（不中断执行线程，`cancel(false)`）。 */
    fun cancel(): Boolean = future.cancel(false)

    /** 任务是否已完成（正常完成 / 异常终止 / 被取消）。 */
    val isDone: Boolean
        get() = future.isDone
}
