package yux.compiler.irgen

import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrType
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.sema.JvmClassSymbol
import yux.compiler.sema.JvmExtensions
import yux.compiler.sema.JvmMethodSymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.TypeAssignability

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
    fun resolveInstanceMethod(receiverType: SemaType, name: String, argTypes: List<SemaType> = emptyList()): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        if (name == "sendMessage") {
        }
        // JavaBean 属性读取优先：getX() / isX()（S-8.5.2；镜像 memberLookup 的 propertyType 在前）
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        jc.methods.firstOrNull { it.name == "get$cap" && it.params.isEmpty() }?.let {
            return jvmCall(it, jc.qualifiedName)
        }
        jc.methods.firstOrNull { it.name == "is$cap" && it.params.isEmpty() && it.returnType == SemaType.BOOLEAN }
            ?.let { return jvmCall(it, jc.qualifiedName) }
        // 重载选择（M9）：按实参类型精确（sameBase 忽略可空）/可赋值匹配
        // （`sendMessage(String)` 不落到 BaseComponent；`String.replace(CharSequence)` 正确）
        val byName = jc.methods.filter { it.name == name }
        val chosen = if (argTypes.isEmpty()) {
            byName.firstOrNull()
        } else {
            byName.firstOrNull { m ->
                m.params.size == argTypes.size &&
                    m.params.indices.all { i -> TypeAssignability.sameBase(argTypes[i], m.params[i]) }
            } ?: byName.firstOrNull { m ->
                m.params.size == argTypes.size &&
                    m.params.indices.all { i -> TypeAssignability.isAssignable(argTypes[i], m.params[i]) }
            } ?: byName.firstOrNull()
        }
        return chosen?.let { jvmCall(it, jc.qualifiedName) }
    }

    /** 静态方法：`System.currentTimeMillis()`。按实参类型匹配重载（Math.max 等）。 */
    fun resolveStaticMethod(receiverType: SemaType, name: String, argTypes: List<SemaType> = emptyList()): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        val byName = jc.staticMethods.filter { it.name == name }
        val candidates = if (argTypes.isEmpty()) byName else byName.filter { it.params.size == argTypes.size }
        val chosen = if (argTypes.isEmpty()) {
            candidates.firstOrNull()
        } else {
            candidates.firstOrNull { m ->
                m.params.indices.all { i -> TypeAssignability.isExact(argTypes[i], m.params[i]) }
            } ?: candidates.firstOrNull { m ->
                m.params.indices.all { i -> TypeAssignability.isAssignable(argTypes[i], m.params[i]) }
            }
        }
        if (chosen != null) return jvmCall(chosen, jc.qualifiedName)
        // JavaBean getter 属性调用（M9）：`Bukkit.onlinePlayers()` → `getOnlinePlayers()`（Paper API）
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        val getter = jc.staticMethods.firstOrNull { it.name == "get$cap" && it.params.isEmpty() } ?:
            jc.staticMethods.firstOrNull { it.name == "is$cap" && it.params.isEmpty() }
        return getter?.let { jvmCall(it, jc.qualifiedName) }
    }

    /** 静态字段：`System.out`。 */
    fun resolveStaticField(receiverType: SemaType, name: String): IrField? {
        val jc = jvmClassOf(receiverType) ?: return null
        return jc.fields.firstOrNull { it.name == name && it.isStatic }
            ?.let { IrField(it.name, TypeBridge.toIr(it.type), isStatic = true, isFinal = it.isFinal, owner = jc.qualifiedName) }
    }

    /**
     * JVM 扩展（T-M11-3）：注册表成员调用降级为 stdlib 静态调用（receiver 由调用方前置为实参 0）。
     * 注册表条目唯一（不按实参匹配）；未命中返回 null。
     */
    fun resolveJvmExtension(receiverType: SemaType, name: String): IrJvmCall? {
        val jc = jvmClassOf(receiverType) ?: return null
        val entry = JvmExtensions.find(jc.qualifiedName, name) ?: return null
        val owner = classPath.resolve(entry.first) as? JvmClassSymbol ?: return null
        val method = owner.staticMethods.firstOrNull { it.name == entry.second } ?: return null
        return jvmCall(method, owner.qualifiedName)
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
            // JvmClassSymbol.isIterable() 覆盖 Iterable 本身与所有实现类（List/Set/...）
            when {
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
