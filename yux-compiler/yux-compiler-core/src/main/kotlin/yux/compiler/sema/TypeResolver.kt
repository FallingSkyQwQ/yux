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
            // 3. 单名导入
            file.imports.filter { !it.star }.forEach { imp ->
                if (imp.qualifiedName.last() == name) {
                    classPath.resolve(imp.qualifiedName.joinToString("."))?.let { return it }
                }
            }
            // 4. star 导入
            file.imports.filter { it.star }.forEach { imp ->
                classPath.resolveIn(imp.qualifiedName.joinToString("."), name)?.let { return it }
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
        // 限定名：直接按完整路径解析
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
        if (!from.nullable && to.nullable) return sameBase(f, t)
        if (from.nullable && !to.nullable) return false
        return sameBase(f, t)
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
            a.symbol === b.symbol && a.args.size == b.args.size &&
                a.args.zip(b.args).all { (x, y) -> sameBase(x, y) }
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
        return sameBase(x, y)
    }
}
