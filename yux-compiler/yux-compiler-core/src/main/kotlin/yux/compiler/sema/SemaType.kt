package yux.compiler.sema

/**
 * 语义分析阶段的类型表示（T-M3-1 / 02-§8.1 IrType 的先行等价物）。
 *
 * 与 AST 的 [yux.compiler.ast.YxType] 不同：本类型是**已解析、已消除歧义**的
 * 语义类型，携带可空性 [nullable] 与泛型实参 [args]，供类型检查与后续
 * Lowering/IR 直接使用。
 */
sealed class SemaType(
    /** 是否可空（`T?`）。 */
    val nullable: Boolean,
) {
    /** 去可空标记，返回同底类型的非空版本。 */
    abstract fun nonNull(): SemaType

    /** 加可空标记，返回同底类型的可空版本（已可空则原样返回）。 */
    open fun asNullable(): SemaType = if (nullable) this else when (this) {
        is Basic -> Basic(name, true)
        is Declared -> Declared(symbol, args, true)
        is TypeParam -> TypeParam(name, bound, true)
        is Function -> Function(params, ret, true)
        else -> this
    }

    /** 类型可读名（诊断/快照用）。 */
    abstract fun render(): String

    /** 是否「无类型错误」的占位类型。 */
    abstract val isError: Boolean

    // ── 具体类型 ────────────────────────────────────────────────────────────

    /** `Unit`：无返回值函数的返回类型（S-4.1.1）。不可空。 */
    object UnitT : SemaType(nullable = false) {
        override fun nonNull(): SemaType = this
        override fun render(): String = "Unit"
        override val isError: Boolean = false
    }

    /** `Nothing`：空底类型（`throw` 表达式），可赋值给任意类型（S-4.1.1）。 */
    object NothingT : SemaType(nullable = false) {
        override fun nonNull(): SemaType = this
        override fun render(): String = "Nothing"
        override val isError: Boolean = false
    }

    /** 解析失败/错误传播的占位类型。 */
    object ErrorT : SemaType(nullable = false) {
        override fun nonNull(): SemaType = this
        override fun render(): String = "<error>"
        override val isError: Boolean = true
    }

    /** 基础类型：`Int/Long/Float/Double/Boolean/Char/String/Byte/Any`（S-4.1.2）。 */
    class Basic(
        val name: String,
        nullable: Boolean,
    ) : SemaType(nullable) {
        override fun nonNull(): SemaType = if (nullable) Basic(name, false) else this
        override fun render(): String = if (nullable) "$name?" else name
        override val isError: Boolean = false
    }

    /** 声明类型：用户类 / `data` / `service` / JVM 类（S-4.3）。 */
    class Declared(
        val symbol: TypeSymbol,
        val args: List<SemaType>,
        nullable: Boolean,
    ) : SemaType(nullable) {
        override fun nonNull(): SemaType = if (nullable) Declared(symbol, args, false) else this
        override fun render(): String {
            val base = if (args.isEmpty()) symbol.name else "${symbol.name} ${args.joinToString(" ") { it.render() }}"
            return if (nullable) "$base?" else base
        }
        override val isError: Boolean = false
    }

    /** 类型参数（泛型 `T`，S-4.3.3）。 */
    class TypeParam(
        val name: String,
        val bound: SemaType?,
        nullable: Boolean,
    ) : SemaType(nullable) {
        override fun nonNull(): SemaType = if (nullable) TypeParam(name, bound, false) else this
        override fun render(): String = name
        override val isError: Boolean = false
    }

    /** 函数类型 `(A, B) -> R`（S-4.4.1）。 */
    class Function(
        val params: List<SemaType>,
        val ret: SemaType,
        nullable: Boolean,
    ) : SemaType(nullable) {
        override fun nonNull(): SemaType = if (nullable) Function(params, ret, false) else this
        override fun render(): String {
            val ps = params.joinToString(", ") { it.render() }
            val base = "($ps) -> ${ret.render()}"
            return if (nullable) "($base)?" else base
        }
        override val isError: Boolean = false
    }

    /** 推断变量（S-4.5）：局部双向推断期间使用，求解后替换为具体类型。 */
    class InferenceVar(
        val id: Int,
        var solution: SemaType? = null,
    ) : SemaType(nullable = false) {
        override fun nonNull(): SemaType = solution?.nonNull() ?: this
        override fun render(): String = solution?.render() ?: "?T$id"
        override val isError: Boolean = solution?.isError ?: false
    }

    companion object {
        // ── 基础类型单例（方便引用，避免重复分配） ──────────────────────────
        val INT = Basic("Int", false)
        val LONG = Basic("Long", false)
        val FLOAT = Basic("Float", false)
        val DOUBLE = Basic("Double", false)
        val BOOLEAN = Basic("Boolean", false)
        val CHAR = Basic("Char", false)
        val STRING = Basic("String", false)
        val BYTE = Basic("Byte", false)
        val ANY = Basic("Any", false)
        val RANGE = Basic("Range", false)

        /** JVM 数字类型集合（算术运算合法的底类型，S-7.5.1）。 */
        val NUMERIC_NAMES = setOf("Int", "Long", "Float", "Double", "Byte")

        fun basic(name: String, nullable: Boolean = false): Basic = Basic(name, nullable)

        /** 解引用推断变量（S-4.5）：跟随 solution 链到具体类型。 */
        fun resolveVar(t: SemaType): SemaType {
            var cur = t
            while (cur is InferenceVar && cur.solution != null) {
                cur = cur.solution!!
            }
            return cur
        }

        fun isNumeric(t: SemaType): Boolean {
            val resolved = resolveVar(t)
            return !resolved.isError && resolved is Basic && resolved.name in NUMERIC_NAMES
        }
    }
}

/** 可被 [SemaType.Declared] 引用的类型符号（用户声明或 JVM 类）。 */
interface TypeSymbol {
    val name: String
    /** 类型参数名列表（空 = 无泛型，S-4.3.3）。 */
    val typeParams: List<String>
    val isData: Boolean
    val isService: Boolean
    val isInterface: Boolean
    /** 是否由 Yux 源码声明（false = JVM 类）。 */
    val isYuxDeclared: Boolean
}
