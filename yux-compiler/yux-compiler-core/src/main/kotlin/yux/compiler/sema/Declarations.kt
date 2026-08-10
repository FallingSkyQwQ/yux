package yux.compiler.sema

import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxClassMember
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxService
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes

/**
 * 声明两遍式处理（T-M3-3 / 02-§6.2）：收集遍之后解析**成员声明**——
 * 函数参数/返回类型、属性类型、父类与接口；并执行 data/service/override/注解校验。
 */
class Declarations(
    private val symbolTable: SymbolTable,
    private val classPath: ClassPathSymbolProvider,
    private val typeResolver: TypeResolver,
    private val diagnostics: DiagnosticSink,
) {
    fun process(declsByFile: Map<String, List<YxDecl>>) {
        for (file in symbolTable.files) {
            for (decl in file.decls) {
                when (decl) {
                    is YxFunction -> registerTopLevelFunction(file, decl)
                    is YxProperty -> registerTopLevelProperty(file, decl)
                    is YxClass -> fillClass(file, classSymbol(file, decl.name) ?: continue, decl.members, decl)
                    is YxDataClass -> fillDataClass(file, classSymbol(file, decl.name) ?: continue, decl)
                    is YxService -> fillService(file, classSymbol(file, decl.name) ?: continue, decl)
                    else -> Unit
                }
            }
        }
        // override 校验依赖父类已填充（S-8.7.2）
        for (file in symbolTable.files) {
            for (sym in file.types.values.filterIsInstance<YxClassSymbol>()) {
                validateOverrides(sym)
            }
        }
    }

    private fun classSymbol(file: FileScope, name: String): YxClassSymbol? {
        val sym = file.types[name] as? YxClassSymbol
        if (sym == null) {
            diagnostics.error("类型 '$name' 未注册", null, ErrorCodes.UNRESOLVED_TYPE)
        }
        return sym
    }

    // ── 顶层 ────────────────────────────────────────────────────────────────

    private fun registerTopLevelFunction(file: FileScope, decl: YxFunction) {
        Annotations.validate(decl.annotations, setOf(AnnotationTarget.FUNCTION), diagnostics)
        if (file.types.containsKey(decl.name)) {
            diagnostics.error(
                "顶层函数 '${decl.name}' 与同名类型冲突",
                decl.span.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        val existing = file.topLevelFunctions[decl.name].orEmpty()
        if (existing.any { it.params.size == decl.params.size }) {
            diagnostics.error(
                "重复声明函数 '${decl.name}'（签名相同）",
                decl.span.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        val fn = buildFunction(file, owner = null, decl, enclosingTypeParams = emptyList())
        file.topLevelFunctions.getOrPut(decl.name) { mutableListOf() } += fn
        // 扩展函数（M9）：按接收者类型登记，供 `receiver.name(args)` 成员调用查找
        fn.receiverType?.let { receiver ->
            file.extensionFunctions.getOrPut(receiver.render()) { mutableListOf() } += fn
        }
    }

    private fun registerTopLevelProperty(file: FileScope, decl: YxProperty) {
        if (file.topLevelProperties.containsKey(decl.name) || file.types.containsKey(decl.name)) {
            diagnostics.error(
                "重复声明顶层属性 '${decl.name}'",
                decl.span.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        file.topLevelProperties[decl.name] = PropertySymbol(
            name = decl.name,
            type = decl.type?.let { typeResolver.resolve(it, file) },
            isVal = decl.accessors.isNotEmpty() && decl.accessors.none { it.kind == YxAccessorKind.SET },
            owner = null,
            visibility = decl.visibility,
            span = decl.span,
            decl = decl,
        )
    }

    // ── 类 / data / service ─────────────────────────────────────────────────

    private fun fillClass(
        file: FileScope,
        sym: YxClassSymbol,
        members: List<YxClassMember>,
        decl: YxClass,
    ) {
        resolveSupertypes(file, sym, decl.superType, decl.interfaces)
        typeResolver.withTypeParams(sym.typeParams) {
            for (member in members) {
                when (member) {
                    is YxProperty -> registerClassMember(file, sym, buildProperty(file, sym, member))
                    is YxFunction -> {
                        Annotations.validate(member.annotations, setOf(AnnotationTarget.FUNCTION), diagnostics)
                        registerClassMember(file, sym, buildFunction(file, sym, member, sym.typeParams))
                    }
                    is yux.compiler.ast.YxInitBlock -> Unit // S-5.2.5 合法
                }
            }
        }
        Annotations.validate(sym.annotations, setOf(AnnotationTarget.CLASS), diagnostics)
    }

    private fun fillDataClass(file: FileScope, sym: YxClassSymbol, decl: YxDataClass) {
        resolveSupertypes(file, sym, decl.superType, decl.interfaces)
        typeResolver.withTypeParams(sym.typeParams) {
            for (member in decl.members) {
                when (member) {
                    is YxProperty -> registerClassMember(file, sym, buildProperty(file, sym, member))
                    // S-5.3.1：data 类仅允许属性——函数/初始化块报 E0011
                    is YxFunction -> diagnostics.error(
                        "data 类不允许函数成员（S-5.3.1）",
                        member.span.start,
                        ErrorCodes.ILLEGAL_DATA_MEMBER,
                    )
                    is yux.compiler.ast.YxInitBlock -> diagnostics.error(
                        "data 类不允许初始化块（S-5.3.1）",
                        member.span.start,
                        ErrorCodes.ILLEGAL_DATA_MEMBER,
                    )
                }
            }
        }
        Annotations.validate(sym.annotations, setOf(AnnotationTarget.CLASS, AnnotationTarget.DATA), diagnostics)
    }

    private fun fillService(file: FileScope, sym: YxClassSymbol, decl: YxService) {
        typeResolver.withTypeParams(sym.typeParams) {
            for (member in decl.members) {
                when (member) {
                    is YxProperty -> registerClassMember(file, sym, buildProperty(file, sym, member))
                    is YxFunction -> {
                        Annotations.validate(member.annotations, setOf(AnnotationTarget.FUNCTION), diagnostics)
                        registerClassMember(file, sym, buildFunction(file, sym, member, sym.typeParams))
                    }
                    is yux.compiler.ast.YxInitBlock -> diagnostics.error(
                        "service 不允许初始化块",
                        member.span.start,
                        ErrorCodes.ILLEGAL_DATA_MEMBER,
                    )
                }
            }
        }
        Annotations.validate(sym.annotations, setOf(AnnotationTarget.SERVICE), diagnostics)
        validateServiceInjection(sym)
    }

    private fun resolveSupertypes(
        file: FileScope,
        sym: YxClassSymbol,
        superType: yux.compiler.ast.YxType?,
        interfaces: List<yux.compiler.ast.YxType>,
    ) {
        sym.superType = superType?.let { typeResolver.withTypeParams(sym.typeParams) { typeResolver.resolve(it, file) } }
        sym.interfaces = interfaces.map { typeResolver.withTypeParams(sym.typeParams) { typeResolver.resolve(it, file) } }
        if (sym.isYuxDeclared && sym.superType == null) {
            sym.superType = SemaType.ANY
        }
    }

    private fun registerClassMember(file: FileScope, sym: YxClassSymbol, member: Symbol) {
        if (sym.members.any { it.name == member.name && it.kind == member.kind && it !is FunctionSymbol }) {
            diagnostics.error(
                "重复声明成员 '${member.name}'",
                member.span?.start,
                ErrorCodes.DUPLICATE_DECLARATION,
            )
            return
        }
        // 函数允许重载（S-5.5），属性与属性冲突即报
        if (member is FunctionSymbol) {
            val clash = sym.functionsNamed(member.name).any {
                sameSignature(it, member)
            }
            if (clash) {
                diagnostics.error(
                    "重复声明函数 '${member.name}'（签名相同）",
                    member.span?.start,
                    ErrorCodes.DUPLICATE_DECLARATION,
                )
                return
            }
            sym.members += member
        } else if (sym.property(member.name) == null) {
            sym.members += member
        }
    }

    private fun sameSignature(a: FunctionSymbol, b: FunctionSymbol): Boolean =
        a.params.size == b.params.size

    // ── 属性/函数构建 ────────────────────────────────────────────────────────

    private fun buildProperty(file: FileScope, owner: YxClassSymbol, decl: YxProperty): PropertySymbol {
        val type = decl.type?.let { typeResolver.resolve(it, file) }
        Annotations.validate(decl.annotations, setOf(AnnotationTarget.PROPERTY), diagnostics)
        // S-5.2.2/5.2.4：同一访问器不得重复声明
        val getCount = decl.accessors.count { it.kind == YxAccessorKind.GET }
        val setCount = decl.accessors.count { it.kind == YxAccessorKind.SET }
        if (getCount > 1 || setCount > 1) {
            diagnostics.error(
                "属性 '${decl.name}' 重复声明访问器（S-5.2.4）",
                decl.span.start,
                ErrorCodes.ILLEGAL_ACCESSOR,
            )
        }
        val hasSet = decl.accessors.any { it.kind == YxAccessorKind.SET }
        // S-5.2.1：默认自动生成 getter+setter（可变）；仅 data 类默认只读（S-5.3.1）
        val isVal = if (owner.isData) !hasSet else (decl.accessors.isNotEmpty() && !hasSet)
        return PropertySymbol(decl.name, type, isVal, owner, decl.visibility, decl.span, decl)
    }

    private fun buildFunction(
        file: FileScope,
        owner: YxClassSymbol?,
        decl: YxFunction,
        enclosingTypeParams: List<String>,
    ): FunctionSymbol {
        val scope = enclosingTypeParams + decl.typeParams.map { it.name }
        return typeResolver.withTypeParams(scope) {
            // 函数参数类型由解析器结构性保证（`name:Type`，S-4.5.3）
            val receiver = decl.receiver?.let { typeResolver.resolve(it, file) }
            // 扩展函数（M9）：receiver 作为第 0 参数（隐式 this），供调用匹配与 IRGen 参数表
            val receiverParam = receiver?.let { ParameterSymbol("this", it, hasDefault = false, decl.span) }
            val params = listOfNotNull(receiverParam) + decl.params.map { p ->
                Annotations.validate(p.annotations, setOf(AnnotationTarget.PARAMETER), diagnostics)
                ParameterSymbol(p.name, typeResolver.resolve(p.type, file), p.defaultValue != null, p.span)
            }
            val ret = decl.returnType?.let { typeResolver.resolve(it, file) }
            FunctionSymbol(decl.name, params, ret, decl.isAsync, decl.isOverride, owner, receiver, decl.visibility, decl.span, decl)
        }
    }

    // ── service 注入图（S-5.4.1）─────────────────────────────────────────────

    private fun validateServiceInjection(sym: YxClassSymbol) {
        val serviceByName = symbolTable.files.flatMap { it.types.values }
            .filterIsInstance<YxClassSymbol>()
            .filter { it.isService }
            .associateBy { it.name }

        // S-5.4.1：非注入属性（类型非 service）必须提供初始值
        for (prop in sym.properties()) {
            val t = prop.type
            if (t == null || t.isError) continue
            val injected = (t as? SemaType.Declared)?.symbol?.isService == true
            if (!injected && prop.decl?.initializer == null) {
                diagnostics.error(
                    "service 属性 '${prop.name}'（非注入类型）缺少初始值（S-5.4.1）",
                    prop.span?.start,
                    ErrorCodes.SERVICE_PROPERTY_NO_INIT,
                )
            }
        }

        // 循环依赖检测（有向图 DFS）
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun dfs(name: String): Boolean {
            if (visiting.contains(name)) return true
            if (visited.contains(name)) return false
            visiting.add(name)
            val node = serviceByName[name] ?: return false
            for (prop in node.properties()) {
                val target = (prop.type as? SemaType.Declared)?.symbol?.name
                if (target != null && serviceByName.containsKey(target)) {
                    if (dfs(target)) return true
                }
            }
            visiting.remove(name)
            visited.add(name)
            return false
        }
        for (name in serviceByName.keys) {
            if (dfs(name)) {
                diagnostics.error(
                    "service 依赖注入图存在循环: $name",
                    sym.span?.start,
                    ErrorCodes.SERVICE_CYCLE,
                )
                return
            }
        }
    }

    // ── override 校验（S-8.7.2）──────────────────────────────────────────────

    private fun validateOverrides(sym: YxClassSymbol) {
        for (fn in sym.functions().filter { it.isOverride }) {
            // S-8.7.2：父类链 / 接口（均为 Yux 类或 JVM 类）中存在同名同元数成员即合法
            if (!hasInheritedFunction(sym, fn.name, fn.params.size)) {
                diagnostics.error(
                    "override 函数 '${fn.name}' 无对应父类成员",
                    fn.span?.start,
                    ErrorCodes.OVERRIDE_NO_SUPER,
                )
            }
        }
    }

    /** override 目标查找：父类链（含父类自身接口）+ 本类接口（传递）。 */
    private fun hasInheritedFunction(sym: YxClassSymbol, name: String, arity: Int): Boolean {
        when (val parent = (sym.superType as? SemaType.Declared)?.symbol) {
            // 父类链 + 父类接口：父类自身的 functionsIncludingSuper 已覆盖其祖先链与接口
            is YxClassSymbol -> if (parent.functionsIncludingSuper(name).any { it.params.size == arity }) return true
            is JvmClassSymbol -> if (parent.allMethods().any { it.name == name && it.params.size == arity }) return true
            else -> Unit
        }
        // 本类接口（传递闭包，仅 Yux 声明类；JVM 接口不要求 override）
        for (iface in sym.interfaceSymbols()) {
            if (iface.functionsNamed(name).any { it.params.size == arity }) return true
        }
        return false
    }
}
