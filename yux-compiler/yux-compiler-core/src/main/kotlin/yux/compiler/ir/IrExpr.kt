package yux.compiler.ir

/**
 * IR 表达式（T-M4-2 / 02-§8.2）。
 *
 * 02-§8.2 的表达式集合补充：表达式位置构造 [New]（文档仅列语句位置，但 `Type(args)`
 * 作为值不可避免）、逻辑非 [Not]、类型检查 [IsType]（`is T`，智能转型依赖）。`as T`
 * 与数值转换共用 [Convert]，由后端按源/目标类型区分（CHECKCAST vs i2l）。
 */
sealed interface IrExpr {
    /**
     * 表达式可丢弃判定（StmtGen 表达式语句门 + BasicOpt 死代码消除共用，02-§8.4）：
     * 可安全丢弃 = 无副作用且不抛异常。**递归检查操作数**——`a() == b()` 作为语句
     * 时，操作数中的调用有副作用，整个表达式不可丢弃。
     * 可能抛异常者不可丢弃：除/模（除零）、可空接收者字段读取（NPE）、强制 Convert
     * （CCE）、Invoke/New/StringTemplate/FnInvoke（任意被调代码）。
     */
    fun isDiscardable(): Boolean = when (this) {
        is Const, is This, is LocalRead, is ClassLiteral -> true
        is ArrayLoad -> base.isDiscardable() && index.isDiscardable()
        is Lambda -> captures.all { it.isDiscardable() }
        is FieldRead -> receiver == null || receiver is This // 静态/this 字段读安全；其他接收者可能 NPE
        is Arith -> op != ArithOp.DIV && op != ArithOp.MOD && l.isDiscardable() && r.isDiscardable()
        is Compare -> l.isDiscardable() && r.isDiscardable()
        is Not -> operand.isDiscardable()
        is Neg -> operand.isDiscardable()
        is IsType -> expr.isDiscardable()
        is NullGuard -> expr.isDiscardable() // 守卫吞掉 NPE，可丢弃（结果未用）
        is FnInvoke -> false // 函数调用可能有副作用
        else -> false // Invoke/New/Convert/StringTemplate 不保证安全
    }

    /** 尽力推导表达式类型（空守卫折叠/优化器/快照用）；无法确定返回 null。 */
    fun inferType(): IrType? = when (this) {
        is Const -> when (value) {
            is Int -> IrType.INT
            is Long -> IrType.LONG
            is Float -> IrType.FLOAT
            is Double -> IrType.DOUBLE
            is Boolean -> IrType.BOOLEAN
            is Char -> IrType.CHAR
            is Byte -> IrType.BYTE
            is String -> IrType.STRING
            null -> IrType.Nothing
            else -> IrType.ANY
        }
        is LocalRead -> local.type
        is FieldRead -> field.type
        is New -> type
        is Arith -> arithResultType(op, l, r)
        is Compare, is Not, is IsType -> IrType.BOOLEAN
        is Neg -> operand.inferType()
        is Convert -> to
        is Invoke -> target.retType
        is FnInvoke -> (fn.inferType() as? IrType.Function)?.ret
        is StringTemplate -> IrType.STRING
        is ClassLiteral -> IrType.ANY
        is ArrayLoad -> elemType
        is NullGuard -> expr.inferType()
        is Lambda -> null
        is This -> null
    }

    /** 常量。 */
    data class Const(
        val value: Any?,
    ) : IrExpr

    /** JVM 数组元素读取 `a[i]`（S-6.4.1 数组迭代/索引访问；后端按元素类型发 xALOAD）。 */
    data class ArrayLoad(
        val base: IrExpr,
        val index: IrExpr,
        val elemType: IrType,
    ) : IrExpr

    /**
     * 类字面量（S-8.3 `deserialize(json, PlayerData)` 的运行时值）：
     * 表达式位置的类型引用编译为 JVM 类常量（`ldc Type.class`）。
     */
    data class ClassLiteral(
        val type: IrType,
    ) : IrExpr

    /** 当前实例引用（隐式 this 的实例成员访问；静态上下文不使用）。 */
    data object This : IrExpr

    /** 局部读取。 */
    data class LocalRead(
        val local: IrLocal,
    ) : IrExpr

    /** 字段读取（[receiver]=null 为静态字段）。 */
    data class FieldRead(
        val receiver: IrExpr?,
        val field: IrField,
    ) : IrExpr

    /** 对象构造（表达式位置）：`Type(args)`。 */
    data class New(
        val type: IrType,
        val args: List<IrExpr>,
    ) : IrExpr

    /** 算术运算。 */
    data class Arith(
        val op: ArithOp,
        val l: IrExpr,
        val r: IrExpr,
    ) : IrExpr

    /** 比较运算（结果为 Boolean）。 */
    data class Compare(
        val op: CompareOp,
        val l: IrExpr,
        val r: IrExpr,
    ) : IrExpr

    /** 逻辑非 `!x`。 */
    data class Not(
        val operand: IrExpr,
    ) : IrExpr

    /** 一元负 `-x`。 */
    data class Neg(
        val operand: IrExpr,
    ) : IrExpr

    /** 转换（数值转换 / `as T` 强制转换，后端按类型区分）。 */
    data class Convert(
        val expr: IrExpr,
        val to: IrType,
    ) : IrExpr

    /** 类型检查 `x is T`（S-6.3.1 智能转型基础）。 */
    data class IsType(
        val expr: IrExpr,
        val type: IrType,
    ) : IrExpr

    /** 方法调用（静态 [receiver]=null；否则实例调用）。 */
    data class Invoke(
        val target: IrCallable,
        val receiver: IrExpr?,
        val args: List<IrExpr>,
        /** super 调用：对父类非虚拟调用（INVOKESPECIAL，S-8.7.1）。 */
        val isSuper: Boolean = false,
    ) : IrExpr

    /** 函数类型值调用（S-7.4 高阶函数）：`fn(args)`，[fn] 为函数类型表达式（LocalRead/Lambda）。 */
    data class FnInvoke(
        val fn: IrExpr,
        val args: List<IrExpr>,
    ) : IrExpr

    /** 字符串插值（02-§9.3：StringBuilder.append 序列；纯文本段为 Const(String)）。 */
    data class StringTemplate(
        val parts: List<IrExpr>,
    ) : IrExpr

    /** 空安全守卫（02-§8.2 / §9.4）：接收者为 null 时结果退化为类型默认值。 */
    data class NullGuard(
        val expr: IrExpr,
    ) : IrExpr

    /** Lambda 引用：目标为合成方法（内部类/方法引用由后端决定，02-§9.1）。 */
    data class Lambda(
        val target: IrMethodRef,
        /** 闭包捕获实参（调用点求值；合成方法对应追加捕获参数）。 */
        val captures: List<IrExpr> = emptyList(),
    ) : IrExpr

    private fun arithResultType(op: ArithOp, l: IrExpr, r: IrExpr): IrType? {
        val lt = l.inferType()
        val rt = r.inferType()
        if (op == ArithOp.ADD && (lt is IrType.Basic && lt.name == "String" || rt is IrType.Basic && rt.name == "String")) {
            return IrType.STRING
        }
        val names = listOfNotNull(lt, rt).filterIsInstance<IrType.Basic>().map { it.name }
        if (names.isEmpty() || names.any { it !in IrType.NUMERIC_NAMES }) return null
        return when {
            "Double" in names -> IrType.DOUBLE
            "Float" in names -> IrType.FLOAT
            "Long" in names -> IrType.LONG
            else -> IrType.INT
        }
    }
}

/** 算术运算符（02-§8.2 的 Op）。 */
enum class ArithOp { ADD, SUB, MUL, DIV, MOD }

/** 比较运算符。 */
enum class CompareOp { EQ, NE, LT, LE, GT, GE }

/** 调用目标（Yux 方法引用或 JVM 方法，02-§8.2 的 Invoke(method) 具体化）。 */
sealed interface IrCallable {
    /** 返回类型（供 [IrExpr.Invoke] 推导）。 */
    val retType: IrType

    /** 可读名（快照）。 */
    val displayName: String
}

/** Yux 声明方法引用。 */
class IrMethodRef(
    val method: IrMethod,
) : IrCallable {
    override val retType: IrType get() = method.returnType
    override val displayName: String get() =
        if (method.owner != null) "${method.owner!!.name}.${method.name}" else method.name
}

/** JVM 方法调用（互操作，S-8.5；[owner] 为 JVM 限定名，如 `java.lang.String`）。 */
class IrJvmCall(
    val name: String,
    val owner: String,
    val static: Boolean,
    val params: List<IrType>,
    override val retType: IrType,
) : IrCallable {
    override val displayName: String get() = if (static) "$owner.$name" else "$owner#$name"
}
