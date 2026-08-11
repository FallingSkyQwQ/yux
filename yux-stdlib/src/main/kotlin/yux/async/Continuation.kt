package yux.async

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool

/**
 * 协程续延（async fun CPS 状态机运行时，T-M14-*）。
 *
 * `async fun` 经编译器降级为状态机类，该类实现本接口：await 挂起时把自身
 * 注册为回调，恢复线程经 [resume] / [resumeWithException] 驱动下一状态。
 */
interface Continuation {
    /** 以正常结果恢复。 */
    fun resume(value: Any?)

    /** 以异常恢复（await 挂起可取消：被 await 的 future 取消时以 CancellationException 到达）。 */
    fun resumeWithException(t: Throwable)
}

/**
 * 可驱动的状态机（编译器生成的 async fun / `async { }` 状态机类实现）。
 * [Continuations.launch] 在池线程上驱动 [invokeSuspend]（S-8.4.1 的 `async { }` 语义）。
 */
interface Suspendable {
    /** 驱动状态机：返回挂起哨兵或最终结果。 */
    fun invokeSuspend(): Any?
}

/** 协程运行时工具（编译器生成的挂起/门面代码调用入口）。 */
object Continuations {

    /**
     * 挂起哨兵：`suspendEntry`/await 代码返回它表示「已挂起，未来经 resume 恢复」。
     * `@JvmField`：编译器生成的状态机类以 GETSTATIC 直接读取。
     */
    @JvmField
    val SUSPENDED: Any? = SUSPENDED_MARKER

    /**
     * `await <future>` 的结果包装：future 已完成但以异常结束（避免内联抛逃出状态机驱动）。
     * `@JvmField`：状态机类以 GETFIELD 直接读取 `throwable`。
     */
    class PendingException(@JvmField val throwable: Throwable)

    /** `await <Task>`（S-8.4.2）：委托 [tryAwait] 于 Task 内部 future。 */
    @JvmStatic
    fun tryAwait(task: Task, cont: Continuation): Any? = tryAwait(task.future, cont)

    /**
     * 在 future 上尝试挂起（await 的运行时核心）：
     * - future 未完成 → 注册回调后返回 [SUSPENDED]（调用方应返回 SUSPENDED）；
     * - future 已完成 → 返回结果值（异常完成时返回 [PendingException]），不递归 resume；
     * - future 已取消 → 回调收到 CancellationException，经 [resumeWithException] 恢复
     *   （await 挂起可取消；CancellationException 由状态机的 catch 自动重抛）。
     */
    @JvmStatic
    fun tryAwait(future: CompletableFuture<Any?>, cont: Continuation): Any? {
        if (future.isDone) {
            return try {
                future.getNow(null)
            } catch (e: CompletionException) {
                PendingException(unwrap(e.cause ?: e))
            } catch (e: CancellationException) {
                // getNow 对已取消 future 抛裸 CancellationException（非 CompletionException 包装）
                PendingException(e)
            }
        }
        future.whenComplete { value, throwable ->
            if (throwable != null) {
                cont.resumeWithException(unwrap(throwable))
            } else {
                cont.resume(value)
            }
        }
        return SUSPENDED
    }

    /** 异常解包：ExecutionException/CompletionException 剥壳取 cause。 */
    @JvmStatic
    fun unwrap(t: Throwable): Throwable =
        when {
            t is ExecutionException && t.cause != null -> t.cause!!
            t is CompletionException && t.cause != null -> t.cause!!
            else -> t
        }

    /** 门面完成续延：状态机结果写入 CompletableFuture（同步门面的结果交付通道）。 */
    class FutureCompletion(@JvmField val future: CompletableFuture<Any?>) : Continuation {
        override fun resume(value: Any?) {
            future.complete(value)
        }

        override fun resumeWithException(t: Throwable) {
            future.completeExceptionally(t)
        }
    }

    /** 新建门面结果 future。 */
    @JvmStatic
    fun newFuture(): CompletableFuture<Any?> = CompletableFuture()

    /** CompletableFuture → Task 门面包装。 */
    @JvmStatic
    fun wrap(future: CompletableFuture<Any?>): Task = Task(future)

    /**
     * `async { }` 块运行时入口（S-8.4.1）：在 ForkJoinPool 上驱动状态机，立即返回 Task。
     * 状态机同步完成时结果经 [completion] 写入其 future；挂起后经 resume 恢复
     * 仍由状态机自身的 completion 交付（同一 future）。
     */
    @JvmStatic
    fun launch(sm: Suspendable, completion: FutureCompletion): Task {
        ForkJoinPool.commonPool().execute {
            try {
                val r = sm.invokeSuspend()
                if (r !== SUSPENDED) completion.resume(r)
            } catch (t: Throwable) {
                completion.resumeWithException(unwrap(t))
            }
        }
        return wrap(completion.future)
    }

    private object SUSPENDED_MARKER {
        override fun toString(): String = "SUSPENDED"
    }
}
