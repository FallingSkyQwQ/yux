package yux.compiler.irgen

import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrType
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.sema.JvmClassSymbol
import yux.compiler.sema.JvmMethodSymbol
import yux.compiler.sema.SemaType

/**
 * JVM 互操作解析（T-M4-4，S-8.5）：给定接收者语义类型与成员名，解析出
 * JVM 方法/字段调用描述（02-§9.1 映射总表）。
 *
 * 与 [yux.compiler.sema.TypeChecker] 的 `jvmForBasic`/`memberLookup` 保持一致：
 * 基础类型映射到 JVM 装箱类；JavaBean 属性按 `getX/isX` 访问器解析。
 */
class JvmCallResolver(
    private val classPath: ClassPathSymbolProvider,
) {

    /** 实例方法（JavaBean getter 优先，与 TypeChecker.memberLookup 一致）：`s.length` → `String#length()`。 */
    fun resolveInstanceMethod(receiverType: SemaType, name: String): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        // JavaBean 属性读取优先：getX() / isX()（S-8.5.2；镜像 memberLookup 的 propertyType 在前）
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        jc.methods.firstOrNull { it.name == "get$cap" && it.params.isEmpty() }?.let {
            return jvmCall(it, jc.qualifiedName)
        }
        jc.methods.firstOrNull { it.name == "is$cap" && it.params.isEmpty() && it.returnType == SemaType.BOOLEAN }
            ?.let { return jvmCall(it, jc.qualifiedName) }
        jc.methodNamed(name)?.let { return jvmCall(it, jc.qualifiedName) }
        return null
    }

    /** 静态方法：`System.currentTimeMillis()`。 */
    fun resolveStaticMethod(receiverType: SemaType, name: String): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        return jc.staticMethods.firstOrNull { it.name == name }?.let { jvmCall(it, jc.qualifiedName) }
    }

    /** 静态字段：`System.out`。 */
    fun resolveStaticField(receiverType: SemaType, name: String): IrField? {
        val jc = jvmClassOf(receiverType) ?: return null
        return jc.fields.firstOrNull { it.name == name && it.isStatic }
            ?.let { IrField(it.name, TypeBridge.toIr(it.type), isStatic = true, isFinal = it.isFinal) }
    }

    /** JavaBean setter：`p.name = x` → `setName(x)`。 */
    fun resolveInstanceSetter(receiverType: SemaType, name: String): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        val setter = jc.methods.firstOrNull { it.name == "set$cap" && it.params.size == 1 } ?: return null
        return IrJvmCall(
            name = setter.name,
            owner = jc.qualifiedName,
            static = false,
            params = setter.params.map { TypeBridge.toIr(it) },
            retType = IrType.Void,
        )
    }

    /** 元素类型（S-6.4.1 迭代）：镜像 TypeChecker.elementType，供 for 循环展开。 */
    fun elementType(iterable: SemaType): SemaType? = when (iterable) {
        is SemaType.Basic -> when (iterable.name) {
            "Range" -> SemaType.INT
            "Iterable" -> SemaType.ANY
            "String" -> SemaType.CHAR
            "Array" -> SemaType.ANY
            else -> null
        }
        is SemaType.Declared -> {
            val sym = iterable.symbol
            when {
                sym is JvmClassSymbol && sym.qualifiedName == "java.lang.Iterable" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                sym is JvmClassSymbol && sym.qualifiedName == "java.util.List" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                sym is JvmClassSymbol && sym.qualifiedName == "java.util.Set" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                sym is JvmClassSymbol && sym.isIterable() -> iterable.args.getOrNull(0) ?: SemaType.ANY
                sym is yux.compiler.sema.YxClassSymbol && iterable.args.isNotEmpty() -> iterable.args[0]
                else -> null
            }
        }
        is SemaType.InferenceVar -> iterable.solution?.let { elementType(it) }
        else -> null
    }

    /** 迭代器归属：Yux Range → `yux.core.Range`，JVM 类 → 其限定名，其余回退 Iterable。 */
    fun iteratorOwner(iterable: SemaType): String = when (val t = SemaType.resolveVar(iterable)) {
        is SemaType.Basic -> if (t.name == "Range") "yux.core.Range" else "java.lang.Iterable"
        is SemaType.Declared -> (t.symbol as? JvmClassSymbol)?.qualifiedName ?: "java.lang.Iterable"
        else -> "java.lang.Iterable"
    }

    private fun jvmCall(method: JvmMethodSymbol, owner: String): IrJvmCall = IrJvmCall(
        name = method.name,
        owner = owner,
        static = method.isStatic,
        params = method.params.map { TypeBridge.toIr(it) },
        retType = TypeBridge.toIr(method.returnType),
    )

    /** 语义类型 → JVM 类符号（镜像 TypeChecker.jvmForBasic）。 */
    private fun jvmClassOf(receiverType: SemaType): JvmClassSymbol? = when (val t = SemaType.resolveVar(receiverType)) {
        is SemaType.Declared -> t.symbol as? JvmClassSymbol
        is SemaType.Basic -> jvmForBasic(t.name)?.let { classPath.resolve(it) }
        else -> null
    }

    private fun jvmForBasic(name: String): String? = when (name) {
        "String" -> "java.lang.String"
        "Int" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Float" -> "java.lang.Float"
        "Double" -> "java.lang.Double"
        "Boolean" -> "java.lang.Boolean"
        "Char" -> "java.lang.Character"
        "Byte" -> "java.lang.Byte"
        "Any" -> "java.lang.Object"
        "Iterable" -> "java.lang.Iterable"
        "Range" -> "java.lang.Integer" // 镜像 TypeChecker.jvmForBasic（Range 成员映射到 Integer）
        else -> null
    }
}
