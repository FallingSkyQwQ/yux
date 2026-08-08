package yux.compiler.ir

import yux.compiler.sema.TypeSymbol

/**
 * IR 类型表示（T-M4-3 / 02-§8.1）。
 *
 * 与 M3 的 [yux.compiler.sema.SemaType] 桥接（见 `irgen/TypeBridge.kt`），但保持
 * **JVM 无关**（02-§13.1：不做 ClassDesc 泄漏到 IR），为 Native/WASM 后端预留。
 * 实现要点：
 * - `Basic` 用**类型名**而非 JVM 描述符（`Int/String/...`），JVM 映射由后端负责；
 * - `Declared` 引用 M3 的 [TypeSymbol]（用户类或 JVM 类），比较按符号名等价；
 * - 各节点提供 [render]（可读名，诊断/快照）与 [equivalent]（类型等价比较，T-M4-3 验收）。
 */
sealed class IrType {
    /** 可读名（诊断/快照）。 */
    abstract fun render(): String

    /** 类型等价（T-M4-3 验收）：结构比较 + `Declared` 按符号名 + 实参递归。 */
    abstract fun equivalent(other: IrType): Boolean

    /** 去可空标记，返回同底非空版本（非可空则原样返回）。 */
    open fun nonNull(): IrType = this

    /** 是否为「无类型错误」占位。 */
    open val isError: Boolean = false

    /** 是否可空（`T?`），供空守卫折叠（T-M4-5）与后端判定。 */
    val nullable: Boolean get() = this is Nullable

    // ── 具体类型 ────────────────────────────────────────────────────────────

    /** `Unit`：无返回值。不可空。 */
    data object Void : IrType() {
        override fun render(): String = "Unit"
        override fun equivalent(other: IrType): Boolean = other is Void
    }

    /** `Nothing`：空底类型（`throw` 表达式）。 */
    data object Nothing : IrType() {
        override fun render(): String = "Nothing"
        override fun equivalent(other: IrType): Boolean = other is Nothing
    }

    /** 错误传播占位（对应 [yux.compiler.sema.SemaType.ErrorT]，桥接全函数性）。 */
    data object Error : IrType() {
        override fun render(): String = "<error>"
        override fun equivalent(other: IrType): Boolean = other is Error
        override val isError: Boolean = true
    }

    /** 基础类型：`Int/Long/Float/Double/Boolean/Char/String/Byte/Any/Range`（02-§8.1 的 Basic 无 ClassDesc 版）。 */
    data class Basic(
        val name: String,
    ) : IrType() {
        override fun render(): String = name
        override fun equivalent(other: IrType): Boolean = other is Basic && other.name == name
    }

    /** 可空类型 `T?`。 */
    data class Nullable(
        val base: IrType,
    ) : IrType() {
        override fun render(): String = "${base.render()}?"
        override fun equivalent(other: IrType): Boolean = other is Nullable && base.equivalent(other.base)
        override fun nonNull(): IrType = base
    }

    /** 泛型类型 `Name T1 T2`（位置实参，01-§4.3）。 */
    data class Generic(
        val name: String,
        val args: List<IrType>,
    ) : IrType() {
        override fun render(): String =
            if (args.isEmpty()) name else "$name ${args.joinToString(" ") { it.render() }}"

        override fun equivalent(other: IrType): Boolean =
            other is Generic && other.name == name &&
                args.size == other.args.size &&
                args.zip(other.args).all { (a, b) -> a.equivalent(b) }
    }

    /** 函数类型 `(A, B) -> R`（01-§4.4）。 */
    data class Function(
        val params: List<IrType>,
        val ret: IrType,
    ) : IrType() {
        override fun render(): String =
            "(${params.joinToString(", ") { it.render() }}) -> ${ret.render()}"

        override fun equivalent(other: IrType): Boolean =
            other is Function &&
                params.size == other.params.size &&
                params.zip(other.params).all { (a, b) -> a.equivalent(b) } &&
                ret.equivalent(other.ret)
    }

    /** 声明类型：用户类 / data / service / JVM 类（按符号名等价，跨会话稳定）。 */
    class Declared(
        val symbol: TypeSymbol,
        val args: List<IrType>,
    ) : IrType() {
        override fun render(): String =
            if (args.isEmpty()) symbol.name else "${symbol.name} ${args.joinToString(" ") { it.render() }}"

        override fun equivalent(other: IrType): Boolean =
            other is Declared &&
                other.symbol.name == symbol.name &&
                args.size == other.args.size &&
                args.zip(other.args).all { (a, b) -> a.equivalent(b) }
    }

    /** 类型参数 `T`。 */
    data class TypeParam(
        val name: String,
        val bound: IrType?,
    ) : IrType() {
        override fun render(): String = name
        override fun equivalent(other: IrType): Boolean = other is TypeParam && other.name == name
    }

    // ── 便捷构造与默认值 ─────────────────────────────────────────────────────

    companion object {
        val INT = Basic("Int")
        val LONG = Basic("Long")
        val FLOAT = Basic("Float")
        val DOUBLE = Basic("Double")
        val BOOLEAN = Basic("Boolean")
        val CHAR = Basic("Char")
        val STRING = Basic("String")
        val BYTE = Basic("Byte")
        val ANY = Basic("Any")
        val RANGE = Basic("Range")

        /** JVM 数字类型名（`Convert` 合法性判定）。 */
        val NUMERIC_NAMES = setOf("Int", "Long", "Float", "Double", "Byte")

        /** 数字类型的默认零值（02-§9.4：NullGuard null 分支产生类型默认值）。 */
        fun defaultValue(type: IrType): Any? = when (val t = type.nonNull()) {
            is Basic -> when (t.name) {
                "Int", "Byte" -> 0
                "Long" -> 0L
                "Float" -> 0.0f
                "Double" -> 0.0
                "Boolean" -> false
                "Char" -> '\u0000'
                else -> null
            }
            else -> null
        }
    }
}
