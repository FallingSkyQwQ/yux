package yux.backend.jvm

import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrType
import yux.compiler.sema.JvmClassSymbol
import yux.compiler.sema.YxClassSymbol
import java.lang.reflect.Method

/**
 * JVM 互操作描述符解析（T-M5-7 / 02-§9.1）：IrJvmCall → 真实 JVM 方法描述符。
 *
 * 关键点：IR 的 params/retType 是 **Yux 层类型**，与真实 JVM 描述符可能不一致
 * （如 `Iterator.next()` 真实返回 `Object` 而 IR 按元素类型标注 `Int`）。发射器必须以
 * **真实描述符** 调用，再按 IR 类型做装箱/拆箱适配——本类负责解析真实描述符。
 *
 * 策略：优先反射 `Class.forName(owner)` 按名+实参类型匹配（处理 Math.max 重载）；
 * 反射失败（用户类不在编译类路径）时回退为按 IR 类型合成描述符。
 *
 * M11：以注入 [classLoader] 解析项目类（M10 混合项目 Java/Kotlin 产物目录的 URLClassLoader）；
 * 默认保持编译管线自身类加载器，不传时行为不变。
 */
class JvmDescResolver(
    private val classLoader: ClassLoader = JvmDescResolver::class.java.classLoader,
) {

    /** 解析后的 JVM 方法（含真实返回描述符供发射器适配）。 */
    data class ResolvedMethod(
        val ownerInternal: String,
        val name: String,
        val desc: String,
        val isStatic: Boolean,
        val isInterface: Boolean,
        /** 真实参数描述符（按参数顺序）。 */
        val realParamDescs: List<String>,
        /** 真实返回描述符（void 为 `V`）。 */
        val realRetDesc: String,
    )

    fun resolve(call: IrJvmCall): ResolvedMethod {
        val ownerInternal = call.owner.replace('.', '/')
        val cls = forName(call.owner)
        val real = cls?.let { findMethod(it, call) }
        return if (real != null) {
            ResolvedMethod(
                ownerInternal = ownerInternal,
                name = call.name,
                desc = real.desc,
                isStatic = call.static,
                isInterface = cls.isInterface,
                realParamDescs = real.paramDescs,
                realRetDesc = real.retDesc,
            )
        } else {
            // 反射失败：按 IR 类型合成（用户类跨模块调用等）
            ResolvedMethod(
                ownerInternal = ownerInternal,
                name = call.name,
                desc = syntheticDesc(call),
                isStatic = call.static,
                isInterface = false,
                realParamDescs = call.params.map { JvmTypeMapper.descriptor(it) },
                realRetDesc = JvmTypeMapper.descriptor(call.retType),
            )
        }
    }

    /** 构造器描述符：优先反射（JVM 类），失败按实参 IR 类型合成。 */
    fun constructorDesc(ownerDotted: String, argTypes: List<IrType>): String {
        val cls = forName(ownerDotted)
        if (cls != null) {
            val argClasses = argTypes.map { toClass(it) }
            val ctor = cls.constructors.firstOrNull { c ->
                c.parameterCount == argClasses.size &&
                    c.parameterTypes.indices.all { i -> argClasses[i] == null || c.parameterTypes[i] == argClasses[i] }
            }
            if (ctor != null) {
                return "(" + ctor.parameterTypes.joinToString("") { descOf(it) } + ")V"
            }
        }
        return "(" + argTypes.joinToString("") { JvmTypeMapper.descriptor(it) } + ")V"
    }

    private fun findMethod(cls: Class<*>, call: IrJvmCall): FoundMethod? {
        val argClasses = call.params.map { toClass(it) }
        val candidates = cls.methods.filter { it.name == call.name && it.parameterCount == call.params.size }
        if (candidates.isEmpty()) return null
        val exact = candidates.firstOrNull { m ->
            m.parameterTypes.indices.all { i ->
                val want = argClasses[i]
                want == null || m.parameterTypes[i] == want
            }
        }
        val chosen = exact ?: candidates.first()
        return FoundMethod(
            desc = "(" + chosen.parameterTypes.joinToString("") { descOf(it) } + ")" + descOf(chosen.returnType),
            paramDescs = chosen.parameterTypes.map { descOf(it) },
            retDesc = descOf(chosen.returnType),
        )
    }

    private data class FoundMethod(
        val desc: String,
        val paramDescs: List<String>,
        val retDesc: String,
    )

    private fun syntheticDesc(call: IrJvmCall): String =
        "(" + call.params.joinToString("") { JvmTypeMapper.descriptor(it) } + ")" +
            JvmTypeMapper.descriptor(call.retType)

    private fun forName(dotted: String): Class<*>? = try {
        Class.forName(dotted, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** IrType → JVM Class（用于方法匹配；无法映射返回 null 表示任意匹配）。 */
    private fun toClass(type: IrType): Class<*>? {
        val t = type.nonNull()
        return when (t) {
            is IrType.Basic -> when (t.name) {
                "Int" -> Int::class.javaPrimitiveType
                "Long" -> Long::class.javaPrimitiveType
                "Float" -> Float::class.javaPrimitiveType
                "Double" -> Double::class.javaPrimitiveType
                "Boolean" -> Boolean::class.javaPrimitiveType
                "Char" -> Char::class.javaPrimitiveType
                "Byte" -> Byte::class.javaPrimitiveType
                "String" -> String::class.java
                "Any" -> Any::class.java
                "Range" -> forName("yux.core.Range")
                "Iterator" -> java.util.Iterator::class.java
                "Iterable" -> java.lang.Iterable::class.java
                else -> null
            }
            is IrType.Declared -> when (val s = t.symbol) {
                is YxClassSymbol -> null
                is JvmClassSymbol -> forName(s.qualifiedName)
                else -> null
            }
            is IrType.TypeParam -> Any::class.java
            is IrType.Function -> forName("yux.core.function.Function${t.params.size}")
            else -> null
        }
    }

    private fun descOf(cls: Class<*>): String {
        if (cls.isPrimitive) {
            return when (cls.name) {
                "int" -> "I"
                "long" -> "J"
                "float" -> "F"
                "double" -> "D"
                "boolean" -> "Z"
                "char" -> "C"
                "byte" -> "B"
                "void" -> "V"
                else -> "L${cls.name};"
            }
        }
        if (cls.isArray) return cls.name.replace('.', '/')
        return "L${cls.name.replace('.', '/')};"
    }
}
