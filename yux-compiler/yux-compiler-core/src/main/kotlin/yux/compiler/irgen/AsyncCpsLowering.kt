package yux.compiler.irgen

import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCatch
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.ClassPathSymbolProvider

/**
 * async fun CPS 状态机降级（T-M14）：把 IRGen 产出的 async 方法体（含提升后的
 * [IrStmt.Await] 挂起点）降级为 JVM 状态机。
 *
 * 每个 async fun 产出：
 * - **状态机类** `Owner$name$Sm`：实现 [yux.async.Continuation]；参数/局部变量提升为字段，
 *   `invokeSuspend()` 按 `state` 字段分派到各状态块，await 挂起点结束当前状态块
 *   （置下一状态并返回 [yux.async.Continuations.SUSPENDED]）；`resume/resumeWithException`
 *   驱动下一状态并把最终结果转发给 completion；
 * - **同步门面** `name(args): Task`：构造状态机 + FutureCompletion，急切内联执行到首挂起点；
 * - **挂起入口** `name$suspend(args, Continuation): Any?`：async 上下文内续延传递（不分配 Task）。
 *
 * 异常语义：try/catch/finally 跨挂起点完整支持（异常经状态机路由到 catch 分派状态，
 * finally 按正常/异常/返回三路径展开）；`await` 挂起中被取消的 future 以
 * CancellationException 恢复，经状态机的 catch 自动重抛。
 */
class AsyncCpsLowering(private val module: IrModule) {

    private val classPath = ClassPathSymbolProvider()
    private val resolver = JvmCallResolver(classPath)

    val continuationType: IrType by lazy { jvmType("yux.async.Continuation") }
    val suspendableType: IrType by lazy { jvmType("yux.async.Suspendable") }
    val taskType: IrType by lazy { jvmType("yux.async.Task") }
    val completableFutureType: IrType by lazy { jvmType("java.util.concurrent.CompletableFuture") }
    val pendingExceptionType: IrType by lazy { jvmType("yux.async.Continuations\$PendingException") }
    val futureCompletionType: IrType by lazy { jvmType("yux.async.Continuations\$FutureCompletion") }
    val throwableType: IrType by lazy { jvmType("java.lang.Throwable") }
    val cancellationExceptionType: IrType by lazy { jvmType("java.util.concurrent.CancellationException") }

    private fun jvmType(qualifiedName: String): IrType =
        resolver.resolveJvmClass(qualifiedName)?.let { IrType.Declared(it, emptyList()) } ?: IrType.ANY

    /** 遍历模块：每个 async 方法（门面）及其挂起入口降级为状态机。 */
    fun transform() {
        for (cls in module.classes.toList()) {
            for (method in cls.methods.toList()) {
                if (!method.isAsync) continue
                val suspendEntry = cls.methods.firstOrNull { it.name == "${method.name}\$suspend" }
                if (suspendEntry != null) {
                    StateMachineGenerator(cls, method, suspendEntry, this, module).generate()
                }
            }
        }
    }

    // ── 编译器生成代码与 yux.async.Continuations 的运行时契约 ─────────────────

    /** `Continuations.SUSPENDED` 静态字段读取（@JvmField）。 */
    val suspendedRead: IrExpr
        get() = IrExpr.FieldRead(null, IrField("SUSPENDED", IrType.ANY, isStatic = true, isFinal = false, owner = "yux.async.Continuations"))

    /** `await` 目标 → tryAwait 重载（Task / CompletableFuture）。 */
    fun tryAwaitCall(target: IrExpr, continuation: IrExpr): IrExpr.Invoke {
        val isTask = target.inferType()?.nonNull()?.equivalent(taskType) == true
        return IrExpr.Invoke(
            IrJvmCall(
                name = "tryAwait",
                owner = "yux.async.Continuations",
                static = true,
                params = listOf(if (isTask) taskType else completableFutureType, continuationType),
                retType = IrType.ANY,
            ),
            null,
            listOf(target, continuation),
        )
    }

    fun newFutureCall(): IrExpr.Invoke =
        IrExpr.Invoke(
            IrJvmCall("newFuture", "yux.async.Continuations", static = true, params = emptyList(), retType = completableFutureType),
            null,
            emptyList(),
        )

    fun newFutureCompletion(future: IrExpr): IrExpr.New =
        IrExpr.New(futureCompletionType, listOf(future))

    fun wrapCall(future: IrExpr): IrExpr.Invoke =
        IrExpr.Invoke(
            IrJvmCall("wrap", "yux.async.Continuations", static = true, params = listOf(completableFutureType), retType = taskType),
            null,
            listOf(future),
        )

    fun futureCompleteCall(future: IrExpr, value: IrExpr): IrStmt =
        IrStmt.Call(
            IrJvmCall("complete", "java.util.concurrent.CompletableFuture", static = false, params = listOf(IrType.ANY), retType = IrType.BOOLEAN),
            future,
            listOf(value),
            IrType.BOOLEAN,
        )

    fun futureCompleteExceptionallyCall(future: IrExpr, error: IrExpr): IrStmt =
        IrStmt.Call(
            // 注意：CompletableFuture.completeExceptionally 真实返回 boolean（栈上残留需弹栈）
            IrJvmCall("completeExceptionally", "java.util.concurrent.CompletableFuture", static = false, params = listOf(throwableType), retType = IrType.BOOLEAN),
            future,
            listOf(error),
            IrType.BOOLEAN,
        )

    fun completionResumeCall(completion: IrExpr, value: IrExpr): IrStmt =
        IrStmt.Call(
            IrJvmCall("resume", "yux.async.Continuation", static = false, params = listOf(IrType.ANY), retType = IrType.Void),
            completion,
            listOf(value),
            IrType.Void,
        )

    fun completionResumeWithExceptionCall(completion: IrExpr, error: IrExpr): IrStmt =
        IrStmt.Call(
            IrJvmCall("resumeWithException", "yux.async.Continuation", static = false, params = listOf(throwableType), retType = IrType.Void),
            completion,
            listOf(error),
            IrType.Void,
        )

    /** 状态机类名：`Owner$name$Sm`（JVM `$` 分隔合法；同名方法不同类不冲突）。 */
    fun smClassName(owner: IrClass, method: IrMethod): String = "${owner.name}\$${method.name}\$Sm"
}
