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
 * - 用户类（YxClassSymbol）映射为类名内部名（文件类同规则）；
 * - JVM 类（JvmClassSymbol）映射限定名斜杠化；
 * - 泛型按擦除处理（v0.1：不产出 Signature 属性）。
 */
object JvmTypeMapper {

    /** 内部名（`java/lang/String` / `User`）。 */
    fun internalName(type: IrType): String {
        val t = type.nonNull()
        return when (t) {
            is IrType.Basic -> BASIC_INTERNAL[t.name] ?: "java/lang/Object"
            is IrType.Declared -> when (val s = t.symbol) {
                is YxClassSymbol -> s.name
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
}
