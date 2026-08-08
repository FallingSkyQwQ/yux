package yux.compiler.ir

/**
 * IR 语句（T-M4-2 / 02-§8.2）。
 *
 * 结构化、带类型的抽象字节码（SSA-less，轻量、直接映射 JVM）。02-§8.2 的
 * `Branch/Goto` 需要标签定位点，故补充 [Label] 语句（文档的 Goto 目标不可无
 * 载体表达，此为最小必要补充）。
 */
sealed interface IrStmt {
    /** 标签定位点：`Goto/Branch` 的目标（02-§8.2 补充）。 */
    data class Label(
        val label: IrLabel,
    ) : IrStmt

    /** 局部赋值：`local = value`。 */
    data class LocalAssign(
        val local: IrLocal,
        val value: IrExpr,
    ) : IrStmt

    /** 语句位置调用（结果丢弃）：`foo(args)` / `obj.foo(args)`（表达式位置用 [IrExpr.Invoke]）。 */
    class Call(
        val callee: IrCallable,
        /** 实例接收者（静态调用为 null）。 */
        val receiver: IrExpr?,
        val args: List<IrExpr>,
        /** 被调方法返回类型（供后端决定是否弹栈）。 */
        val ret: IrType,
    ) : IrStmt

    /** 语句位置构造（结果丢弃，通常被优化器移除）：`Type(args)`。 */
    data class New(
        val type: IrType,
        val args: List<IrExpr>,
    ) : IrStmt

    /** 字段访问（[write]=false 读取并丢弃 / true 写入 [value]）。 */
    data class FieldAccess(
        val receiver: IrExpr?,
        val field: IrField,
        val write: Boolean,
        /** 写入值（[write]=true 时必须非 null；读取位置为 null）。 */
        val value: IrExpr?,
    ) : IrStmt

    /** 条件跳转：cond 为真跳 [then]，否则跳 [elseLabel]。 */
    data class Branch(
        val cond: IrExpr,
        val then: IrLabel,
        val elseLabel: IrLabel,
    ) : IrStmt

    /** 无条件跳转。 */
    data class Goto(
        val label: IrLabel,
    ) : IrStmt

    /** 返回（无返回值时为 null）。 */
    data class Return(
        val value: IrExpr?,
    ) : IrStmt

    /** 抛出异常。 */
    data class Throw(
        val value: IrExpr,
    ) : IrStmt

    /** 监视器进入/退出（`synchronized`，02-§8.2）。 */
    data class Monitor(
        val expr: IrExpr,
        val enter: Boolean,
    ) : IrStmt

    /** try/catch/finally（结构化表示，后端映射 JVM 异常表；02-§8.2 的 TryCatch 具体化）。 */
    data class Try(
        val body: List<IrStmt>,
        val catches: List<IrCatch>,
        val finallyBody: List<IrStmt>?,
    ) : IrStmt

    /** 空操作（占位/优化器产物）。 */
    data object Nop : IrStmt
}

/** catch 子句（01-§6.6）。 */
class IrCatch(
    val paramName: String,
    /** null = 捕获全部（`catch e`）。 */
    val type: IrType?,
    val body: List<IrStmt>,
)
