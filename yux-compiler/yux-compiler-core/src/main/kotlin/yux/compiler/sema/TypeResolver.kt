package yux.compiler.sema

import yux.compiler.ast.SourceSpan
import yux.compiler.ast.YxFunctionType
import yux.compiler.ast.YxNamedType
import yux.compiler.ast.YxType
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes

/**
 * AST 类型 → [SemaType] 解析（T-M3-1/3）。
 *
 * 解析顺序（02-§6.3）：当前文件类型 → 同包类型 → 单名导入 → star 导入 →
 * 基础类型 → JDK 内置类路径 → `java.lang` 回退 → 失败按命名约定回退（W0001）。
 * 同时校验泛型实参数目（S-4.3.2）与类型参数作用域（S-4.3.3）。
 */
class TypeResolver(
    private val symbolTable: SymbolTable,
    private val classPath: ClassPathSymbolProvider,
    private val diagnostics: DiagnosticSink,
) {
    /** 类型参数作用域栈（类/函数的 `T`，S-4.3.3）。 */
    private val typeParamStack = ArrayDeque<Map<String, SemaType.TypeParam>>()

    fun <T> withTypeParams(typeParams: List<String>, block: () -> T): T {
        typeParamStack.addLast(typeParams.associate { it to SemaType.TypeParam(it, null, nullable = false) })
        return try {
            block()
        } finally {
            typeParamStack.removeLast()
        }
    }

    /** 解析 [type]；失败时报告 [ErrorCodes.UNRESOLVED_TYPE] 并返回错误占位。 */
    fun resolve(type: YxType, file: FileScope): SemaType = when (type) {
        is YxNamedType -> resolveNamed(type, file)
        is YxFunctionType -> SemaType.Function(
            params = type.params.map { resolve(it, file) },
            ret = resolve(type.ret, file),
            nullable = false,
        )
    }

    private fun resolveNamed(type: YxNamedType, file: FileScope): SemaType {
        // 单段名的特殊解析：类型参数 / 基础类型（S-4.1）
        if (type.segments.size == 1) {
            val name = type.segments.single()
            // 0. 类型参数（S-4.3.3）：`T` 直接解析为类型参数，不接受泛型实参
            typeParamStack.lastOrNull()?.get(name)?.let { tp ->
                if (type.typeArgs.isNotEmpty()) {
                    diagnostics.error(
                        "类型参数 '${tp.name}' 不接受泛型实参（S-4.3.3）",
                        type.span.start,
                        ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH,
                    )
                    return SemaType.ErrorT
                }
                return SemaType.TypeParam(tp.name, tp.bound, type.nullable)
            }
            // 1. 基础类型（Int/Long/.../Unit/Nothing）→ 直接构造（S-4.1）
            Builtins.basicType(name)?.let { basic ->
                if (type.typeArgs.isNotEmpty()) {
                    diagnostics.error(
                        "基础类型 '$name' 不接受泛型实参（S-4.3.2）",
                        type.span.start,
                        ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH,
                    )
                    return SemaType.ErrorT
                }
                return if (type.nullable) basic.asNullable() else basic
            }
        }
        val base = lookup(type.segments, file, type.span)
        if (base == null) {
            diagnostics.error("类型无法解析: ${type.segments.joinToString(".")}", type.span.start, ErrorCodes.UNRESOLVED_TYPE)
            return SemaType.ErrorT
        }
        val args = type.typeArgs.map { resolve(it, file) }
        if (args.isNotEmpty()) {
            val expected = base.typeParams.size
            if (expected == 0) {
                diagnostics.error(
                    "类型 '${base.name}' 不接受泛型实参（S-4.3.2）",
                    type.span.start,
                    ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH,
                )
            } else if (args.size != expected) {
                diagnostics.error(
                    "泛型实参数目不匹配: '${base.name}' 需要 $expected 个实参，实际 ${args.size} 个（S-4.3.2）",
                    type.span.start,
                    ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH,
                )
            }
        }
        return SemaType.Declared(base, args, type.nullable)
    }

    private fun lookup(segments: List<String>, file: FileScope, span: SourceSpan): TypeSymbol? {
        if (segments.size == 1) {
            val name = segments.single()
            // 1. 当前文件类型
            file.types[name]?.let { return it }
            // 2. 同包其他文件
            symbolTable.typeInPackage(file.packageName, name)?.let { return it }
            // 3. 单名导入（Yux 声明类经符号表按包解析，JVM 类经 classpath）
            file.imports.filter { !it.star }.forEach { imp ->
                if (imp.qualifiedName.last() == name) {
                    val importPkg = imp.qualifiedName.dropLast(1).joinToString(".")
                    symbolTable.typeInPackage(importPkg, name)?.let { return it }
                    classPath.resolve(imp.qualifiedName.joinToString("."))?.let { return it }
                }
            }
            // 4. star 导入（Yux 包先于 JVM classpath）
            file.imports.filter { it.star }.forEach { imp ->
                val importPkg = imp.qualifiedName.joinToString(".")
                symbolTable.typesInPackage(importPkg)?.get(name)?.let { return it }
                classPath.resolveIn(importPkg, name)?.let { return it }
            }
            // 5. JDK 内置类路径（List/Map/System/...）
            Builtins.BUILTIN_CLASSPATH[name]?.let { classPath.resolve(it) }?.let { return it }
            // 7. java.lang 回退
            classPath.resolve("java.lang.$name")?.let { return it }
            // 8. 失败：按命名约定回退 + W0001
            if (name.firstOrNull()?.isUpperCase() == true) {
                diagnostics.warning(
                    "无法解析类型 '$name'，按命名约定假定为 JVM 类型（W0001）",
                    span.start,
                    ErrorCodes.TYPE_FALLBACK,
                )
            }
            return null
        }
        // 限定名：Yux 声明类（`com.example.Player`）优先于 JVM classpath 解析
        val pkg = segments.dropLast(1).joinToString(".")
        symbolTable.typeInPackage(pkg, segments.last())?.let { return it }
        return classPath.resolve(segments.joinToString("."))
    }
}

/**
 * 类型可赋值性判定（用于赋值/实参/返回检查）。
 *
 * 规则：
 * - 非空可赋值给可空（子类型反向），反之不行（S-4.2）。
 * - `Nothing` 可赋值给任意类型；`ErrorT` 与目标任意匹配（错误传播）。
 * - 数值字面量收窄由 [Inference] 处理（S-7.5.4）。
 */
object TypeAssignability {
    fun isAssignable(from: SemaType, to: SemaType): Boolean {
        if (from.isError || to.isError) return true
        if (from is SemaType.NothingT) return true
        val f = from.nonNull()
        val t = to.nonNull()
        // T-M11-3：Yux 函数类型 → JVM FunctionN SAM 接口（lambda 实参可传 FunctionN 参数）
        if (f is SemaType.Function && t is SemaType.Declared && t.symbol is JvmClassSymbol) {
            val qn = (t.symbol as JvmClassSymbol).qualifiedName
            if (qn == "yux.core.function.Function0" || qn == "yux.core.function.Function1" ||
                qn == "yux.core.function.Function2" || qn == "yux.core.function.Function3"
            ) {
                return true
            }
        }
        if (!from.nullable && to.nullable) {
            if (sameBase(f, t)) return true
            // Java 互操作：非空基本类型可赋给可空 JVM 接口（String→CharSequence?，S-8.1/8.5）
            if (f is SemaType.Basic && t is SemaType.Declared && t.symbol is JvmClassSymbol) {
                val boxed = jvmBoxedName(f.name) ?: return false
                return jvmImplements(boxed, (t.symbol as JvmClassSymbol).qualifiedName)
            }
            // T-M12：Yux 类继承——子类可赋给可空祖先类型
            if (isYuxSubtypeOf(f, t)) return true
            return false
        }
        if (from.nullable && !to.nullable) return false
        if (sameBase(f, t)) return true
        // Java 互操作（S-8.5 / M9）：基础类型装箱后是否可实现目标 JVM 接口
        // （String→CharSequence、Int→Number 等，`replace(CharSequence, CharSequence)` 依赖）
        if (f is SemaType.Basic && t is SemaType.Declared && t.symbol is JvmClassSymbol) {
            val boxed = jvmBoxedName(f.name) ?: return false
            return jvmImplements(boxed, (t.symbol as JvmClassSymbol).qualifiedName)
        }
        // T-M12：Yux 类继承——子类可赋给祖先类型（Circle extends Shape → Circle 可传给 Shape 参数）
        if (isYuxSubtypeOf(f, t)) return true
        return false
    }

    /** [from] 是否为 [to] 的 Yux 类后代（含自身，沿 superType 链；仅 Yux 声明类）。 */
    private fun isYuxSubtypeOf(from: SemaType, to: SemaType): Boolean {
        val f = from as? SemaType.Declared ?: return false
        val t = to as? SemaType.Declared ?: return false
        val fs = f.symbol as? YxClassSymbol ?: return false
        val ts = t.symbol as? YxClassSymbol ?: return false
        return isYuxSupertypeOf(ts, fs)
    }

    /** 基础类型 → JVM 装箱类名（Int→java.lang.Integer）。 */
    private fun jvmBoxedName(name: String): String? = when (name) {
        "String" -> "java.lang.String"
        "Int" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Float" -> "java.lang.Float"
        "Double" -> "java.lang.Double"
        "Boolean" -> "java.lang.Boolean"
        "Char" -> "java.lang.Character"
        "Byte" -> "java.lang.Byte"
        "Any" -> "java.lang.Object"
        else -> null
    }

    /** 装箱类是否实现/继承目标 JVM 类型（反射判定，失败按 false）。 */
    private fun jvmImplements(boxed: String, target: String): Boolean = try {
        val boxedClass = Class.forName(boxed, false, JvmClassSymbol::class.java.classLoader)
        val targetClass = Class.forName(target, false, JvmClassSymbol::class.java.classLoader)
        targetClass.isAssignableFrom(boxedClass)
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: NoClassDefFoundError) {
        false
    } catch (_: LinkageError) {
        false
    }

    /** 类型精确相等（重载选择用：`Math.max(1,2)` 不得命中 double 重载）。 */
    fun isExact(from: SemaType, to: SemaType): Boolean {
        val f = SemaType.resolveVar(from)
        val t = SemaType.resolveVar(to)
        if (f is SemaType.InferenceVar || t is SemaType.InferenceVar) return false
        if (f.nullable != t.nullable) return false
        val fb = f.nonNull()
        val tb = t.nonNull()
        return when {
            fb is SemaType.ErrorT || tb is SemaType.ErrorT -> true
            fb is SemaType.Basic && tb is SemaType.Basic -> fb.name == tb.name
            fb is SemaType.Declared && tb is SemaType.Declared -> fb.symbol === tb.symbol
            fb is SemaType.Function && tb is SemaType.Function ->
                fb.params.size == tb.params.size &&
                    fb.params.zip(tb.params).all { (x, y) -> isExact(x, y) } &&
                    isExact(fb.ret, tb.ret)
            fb is SemaType.UnitT && tb is SemaType.UnitT -> true
            else -> false
        }
    }

    /** 底层类型结构一致（不含可空性）。 */
    fun sameBase(a: SemaType, b: SemaType): Boolean = when {
        a is SemaType.NothingT || b is SemaType.NothingT -> true
        a is SemaType.ErrorT || b is SemaType.ErrorT -> true
        // Any 为顶层类型（S-4.1.1）：接受一切
        a is SemaType.Basic && a.name == "Any" -> true
        b is SemaType.Basic && b.name == "Any" -> true
        a is SemaType.UnitT -> b is SemaType.UnitT
        a is SemaType.Basic && b is SemaType.Basic -> a.name == b.name
        a is SemaType.TypeParam && b is SemaType.TypeParam -> a.name == b.name
        a is SemaType.TypeParam && b is SemaType.Basic -> true
        a is SemaType.Basic && b is SemaType.TypeParam -> true
        a is SemaType.Declared && b is SemaType.Declared ->
            a.symbol === b.symbol && (
                // JVM 擦除裸类型（Map 无实参）接受任意泛型实参（M9：互操作），否则精确比较
                a.args.isEmpty() || b.args.isEmpty() ||
                    (a.args.size == b.args.size && a.args.zip(b.args).all { (x, y) -> sameBase(x, y) })
                )
        a is SemaType.Function && b is SemaType.Function ->
            a.params.size == b.params.size &&
                a.params.zip(b.params).all { (x, y) -> sameBase(x, y) } &&
                sameBase(a.ret, b.ret)
        else -> false
    }

    /**
     * 两个类型是否「可能存在公共子类型」（用于 `is` 转型可行性，S-6.3.1）。
     * `Any`/类型参数/`Nothing`/错误类型视为可能与任何类型相关；其余按 [sameBase] 判定。
     */
    fun possiblySame(a: SemaType, b: SemaType): Boolean {
        val x = SemaType.resolveVar(a)
        val y = SemaType.resolveVar(b)
        if (x.isError || y.isError) return true
        if (x is SemaType.NothingT || y is SemaType.NothingT) return true
        if (x is SemaType.Basic && x.name == "Any") return true
        if (y is SemaType.Basic && y.name == "Any") return true
        if (x is SemaType.TypeParam || y is SemaType.TypeParam) return true
        if (x is SemaType.UnitT || y is SemaType.UnitT) return x is SemaType.UnitT && y is SemaType.UnitT
        if (sameBase(x, y)) return true
        // T-M12：继承关系下 `is T` 检查可能成立（`Shape is Circle` 当 `Circle extends Shape`）——
        // 沿 superType 链判定 Yux 声明类之间的祖先/后代关系。
        if (x is SemaType.Declared && y is SemaType.Declared) {
            val xs = x.symbol as? YxClassSymbol
            val ys = y.symbol as? YxClassSymbol
            if (xs != null && ys != null && (isYuxSupertypeOf(xs, ys) || isYuxSupertypeOf(ys, xs))) return true
        }
        return false
    }

    /** [ancestor] 是否为 [desc] 的祖先（含自身，沿 superType 链）。 */
    private fun isYuxSupertypeOf(ancestor: YxClassSymbol, desc: YxClassSymbol): Boolean {
        var cur: YxClassSymbol? = desc
        while (cur != null) {
            if (cur === ancestor) return true
            cur = (cur.superType as? SemaType.Declared)?.symbol as? YxClassSymbol
        }
        return false
    }
}
