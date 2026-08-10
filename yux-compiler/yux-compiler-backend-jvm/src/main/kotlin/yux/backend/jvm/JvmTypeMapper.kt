package yux.backend.jvm

import org.objectweb.asm.Type
import yux.compiler.ir.IrType
import yux.compiler.sema.JvmClassSymbol
import yux.compiler.sema.YxClassSymbol

/**
 * IrType → JVM 描述符/内部名映射（T-M5-1/02-§9.1 映射总表）。
 *
 * 规则：
 * - 基本类型映射 JVM 原语描述符（`Int→I`）；`String/Any` 映射引用；
 * - 可空类型在 JVM 上是装箱引用（`Int?→Ljava/lang/Integer;`），与 02-§9.1 一致；
 * - 用户类（YxClassSymbol）映射为**限定名**内部名（`com.example.Player → com/example/Player`，
 *   默认包仍为简单名）；文件类同规则；
 * - JVM 类（JvmClassSymbol）映射限定名斜杠化；
 * - 泛型按擦除处理（v0.1：不产出 Signature 属性）。
 */
object JvmTypeMapper {

    /** 点分限定名 → JVM 内部名（`com.example.Player` → `com/example/Player`；简单名原样返回）。 */
    fun internalClassName(dotted: String): String = dotted.replace('.', '/')

    /** 内部名（`java/lang/String` / `com/example/Player`）。 */
    fun internalName(type: IrType): String {
        val t = type.nonNull()
        return when (t) {
            is IrType.Basic -> BASIC_INTERNAL[t.name] ?: "java/lang/Object"
            is IrType.Declared -> when (val s = t.symbol) {
                is YxClassSymbol -> internalClassName(s.qualifiedName)
                is JvmClassSymbol -> s.qualifiedName.replace('.', '/')
                else -> "java/lang/Object"
            }
            is IrType.Generic -> genericErasure(t)
            is IrType.TypeParam -> t.bound?.let { internalName(it) } ?: "java/lang/Object"
            is IrType.Function -> functionInternal(t) ?: "java/lang/Object"
            else -> "java/lang/Object"
        }
    }

    /** 字段/参数/返回描述符（`I` / `Ljava/lang/String;`）。 */
    fun descriptor(type: IrType): String = when (type) {
        // 可空原语 → 装箱引用（`Int?→Ljava/lang/Integer;`，02-§9.1），保证引用语义（==null/存储）
        is IrType.Nullable -> boxedDescriptor(type.base)
        else -> when (val t = type.nonNull()) {
            is IrType.Void -> "V"
            is IrType.Basic -> BASIC_DESC[t.name] ?: "L${BASIC_INTERNAL[t.name] ?: "java/lang/Object"};"
            is IrType.Declared, is IrType.Generic, is IrType.TypeParam, is IrType.Function ->
                "L${internalName(t)};"
            else -> "Ljava/lang/Object;"
        }
    }

    /** 装箱描述符（可空原语 → 包装类；`Int?→Ljava/lang/Integer;`）。 */
    fun boxedDescriptor(type: IrType): String = when (val t = type.nonNull()) {
        is IrType.Basic -> BOXED_DESC[t.name] ?: "L${internalName(t)};"
        else -> descriptor(t)
    }

    /** 栈宽度：Long/Double 占 2 槽。 */
    fun isWide(type: IrType): Boolean {
        val t = type.nonNull()
        return t is IrType.Basic && (t.name == "Long" || t.name == "Double")
    }

    /** 类型默认值（02-§9.4：NullGuard null 分支/未初始化数值槽）。 */
    fun defaultValue(type: IrType): Any? = IrType.defaultValue(type)

    /** 函数类型 → FunctionN 接口内部名（01-§4.4；arity 0..4，更高返回 null 由调用方给诊断）。 */
    fun functionInternal(type: IrType.Function): String? {
        val arity = type.params.size
        if (arity > 4) return null
        return "yux/core/function/Function$arity"
    }

    private fun genericErasure(t: IrType.Generic): String = when (t.name) {
        "List", "Set", "Collection", "Iterable" -> "java/util/${t.name}"
        "Map" -> "java/util/Map"
        "Optional" -> "java/util/Optional"
        else -> "java/lang/Object"
    }

    private val BASIC_INTERNAL = mapOf(
        "String" to "java/lang/String",
        "Any" to "java/lang/Object",
        "Range" to "yux/core/Range",
        "Iterator" to "java/util/Iterator",
        "Iterable" to "java/lang/Iterable",
        "Array" to "java/lang/Object",
        "Throwable" to "java/lang/Throwable",
        "Exception" to "java/lang/Exception",
    )

    private val BASIC_DESC = mapOf(
        "Int" to "I",
        "Long" to "J",
        "Float" to "F",
        "Double" to "D",
        "Boolean" to "Z",
        "Char" to "C",
        "Byte" to "B",
        "Unit" to "V",
    )

    private val BOXED_DESC = mapOf(
        "Int" to "Ljava/lang/Integer;",
        "Long" to "Ljava/lang/Long;",
        "Float" to "Ljava/lang/Float;",
        "Double" to "Ljava/lang/Double;",
        "Boolean" to "Ljava/lang/Boolean;",
        "Char" to "Ljava/lang/Character;",
        "Byte" to "Ljava/lang/Byte;",
    )

    /**
     * 类字面量内部名（`deserialize(json, Int)` → `java/lang/Integer`）：
     * 基本类型映射装箱类（原语无内部名），引用类型直接取内部名。
     */
    fun classLiteralInternal(type: IrType): String = when (val t = type.nonNull()) {
        is IrType.Basic -> BOXED_DESC[t.name]?.removePrefix("L")?.removeSuffix(";") ?: internalName(t)
        else -> internalName(t)
    }

    /** ASM Type（便捷访问）。 */
    fun asmType(type: IrType): Type = Type.getType(descriptor(type))

    // ── 泛型签名（T-M12 / JVMS §4.7.9.1）──────────────────────────────────────

    /**
     * 位置签名（字段/方法参数/返回）：原语 → 描述符，可空原语 → 装箱引用（与 [descriptor] 一致）。
     * 与 [descriptor] 相等时返回 null（纯擦除，无需 Signature 属性）。
     */
    fun typeSignature(type: IrType): String? {
        val ref = positionSig(type)
        return if (ref == descriptor(type)) null else ref
    }

    private fun positionSig(type: IrType): String {
        val t = type.nonNull()
        return when (t) {
            // nonNull() 已剥离 Nullable，此分支不可达（编译期穷尽性要求）
            is IrType.Nullable -> positionSig(type)
            is IrType.Basic -> when (t.name) {
                "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Unit" -> descriptor(type)
                else -> "L${BASIC_INTERNAL[t.name] ?: "java/lang/Object"};"
            }
            is IrType.Declared -> {
                val internal = when (val s = t.symbol) {
                    is YxClassSymbol -> internalClassName(s.qualifiedName)
                    is JvmClassSymbol -> s.qualifiedName.replace('.', '/')
                    else -> "java/lang/Object"
                }
                "L$internal${typeArgsSig(t.args)};"
            }
            is IrType.Generic -> "L${genericErasure(t)}${typeArgsSig(t.args)};"
            is IrType.TypeParam -> "T${t.name};"
            is IrType.Function -> functionInternal(t)?.let { "L$it;" } ?: "Ljava/lang/Object;"
            IrType.Void -> "V"
            IrType.Nothing, IrType.Error -> "Ljava/lang/Object;"
        }
    }

    /** 类型实参签名：实参位置原语装箱（`Int` → `Ljava/lang/Integer;`）。 */
    private fun typeArgsSig(args: List<IrType>): String =
        if (args.isEmpty()) "" else "<" + args.joinToString("") { argSig(it) } + ">"

    private fun argSig(type: IrType): String {
        val t = type.nonNull()
        return if (t is IrType.Basic) {
            BOXED_DESC[t.name] ?: positionSig(type)
        } else {
            positionSig(type)
        }
    }

    /**
     * 类签名（JVMS §4.7.9.1）：`<T:Ljava/lang/Object;>LSuper<...>;LIface<...>;`。
     * 无类型参数且父类/接口均无泛型时返回 null。
     */
    fun classSignature(typeParams: List<String>, superType: IrType?, interfaces: List<IrType>): String? {
        val genericSuper = superType?.let { typeSignature(it) != null } ?: false
        if (typeParams.isEmpty() && !genericSuper && interfaces.none { typeSignature(it) != null }) {
            return null
        }
        val tp = if (typeParams.isEmpty()) "" else "<" + typeParams.joinToString("") { "$it:Ljava/lang/Object;" } + ">"
        val sup = superType?.let { positionSig(it) } ?: "Ljava/lang/Object;"
        val ifs = interfaces.joinToString("") { positionSig(it) }
        return "$tp$sup$ifs"
    }

    /**
     * 方法签名（JVMS §4.7.9.1）：`(P...)R`。与描述符一致时返回 null（纯擦除）。
     */
    fun methodSignature(params: List<IrType>, ret: IrType): String? {
        val sig = "(" + params.joinToString("") { positionSig(it) } + ")" + positionSig(ret)
        val desc = "(" + params.joinToString("") { descriptor(it) } + ")" + descriptor(ret)
        return if (sig == desc) null else sig
    }
}
