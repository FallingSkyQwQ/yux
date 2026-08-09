package yux.compiler.irgen

import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxAnnotation
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxClassMember
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxInitBlock
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxService
import yux.compiler.ast.YxType
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrAnnotation
import yux.compiler.ir.IrAnnotationArg
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrProperty
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.AnalysisResult
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.sema.FileScope
import yux.compiler.sema.FunctionSymbol
import yux.compiler.sema.PropertySymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.TypeResolver
import yux.compiler.sema.YxClassSymbol

/**
 * AST → IR 生成器（T-M4-4 / 02-§8，06-§9.2 的 IRGen）。
 *
 * 两遍式：
 * 1. **骨架遍**：为每个文件建文件类（顶级函数/属性为静态成员，05-§5.3），
 *    为每个类建字段/属性/构造器/方法骨架，并登记 `FunctionSymbol→IrMethod`、
 *    `PropertySymbol→IrField` 映射供调用解析；
 * 2. **主体遍**：为登记的骨架生成方法体（if→Branch、for→迭代器展开等下沉，
 *    StmtGen/ExprGen）。
 *
 * 依赖 M3 的 [AnalysisResult]：表达式类型（含智能转型结果）、名称解析、
 * 空守卫插入点（[guardSet]）——IRGen 只消费已通过语义检查的输入。
 */
class IRGen(
    private val analysis: AnalysisResult,
    classLoader: ClassLoader = ClassPathSymbolProvider::class.java.classLoader,
) {
    private val module = IrModule()
    private val classPath = ClassPathSymbolProvider(classLoader)
    private val typeResolver = TypeResolver(analysis.symbolTable, classPath, DiagnosticSink())
    val resolver = JvmCallResolver(classPath)

    /** Yux 函数符号 → 其 IrMethod（调用解析）。 */
    val functionMethods = mutableMapOf<FunctionSymbol, IrMethod>()
    /** 扩展函数（M9）：当前函数体 receiver 局部名（非 null 时 `this` 读该局部）。 */
    var extensionReceiverName: String? = null

    /** 属性符号 → backing 字段。 */
    val propertyFields = mutableMapOf<PropertySymbol, IrField>()

    /** 属性符号 → IrProperty（含 getter/setter；属性访问走访问器，S-5.2/02-§9.1）。 */
    val propertyAccessors = mutableMapOf<PropertySymbol, IrProperty>()

    /** 空安全守卫插入点（T-M3-7 产物）。 */
    val guardSet: Set<YxMemberAccess> = analysis.guardPoints.map { it.node }.toSet()

    private val bodyGenerators = mutableMapOf<IrMethod, (MethodGen) -> Unit>()
    private var lambdaCounter = 0

    val exprTypes: Map<yux.compiler.ast.YxExpr, SemaType> get() = analysis.exprTypes
    val resolvedRefs: Map<yux.compiler.ast.YxNode, yux.compiler.sema.Symbol> get() = analysis.resolvedRefs

    fun generate(declsByFile: Map<String, List<YxDecl>>): IrModule {
        for ((path, decls) in declsByFile) {
            registerFile(path, decls, analysis.symbolTable.fileFor(path))
        }
        for (cls in module.classes) {
            // 快照：Lambda 下沉会向 cls.methods 追加合成方法（newLambdaMethod）
            for (method in cls.methods.toList()) {
                bodyGenerators[method]?.invoke(MethodGen(method, cls))
            }
        }
        return module
    }

    /** 文件类 + 文件级声明注册（骨架遍）。 */
    private fun registerFile(path: String, decls: List<YxDecl>, fileScope: FileScope) {
        val baseName = fileClassName(path)
        // 顶层类与文件类同名时（如 HomeData.yux 内 `data HomeData`），文件类名加 `_File` 后缀
        // 避免 registerClass 的 data 类被文件类遮蔽（M9：构造解析 `classNamed` 命中非文件类）。
        val topLevelTypeNames = decls.filterIsInstance<yux.compiler.ast.YxClass>()
            .map { it.name }
            .toSet() + decls.filterIsInstance<yux.compiler.ast.YxDataClass>().map { it.name } +
            decls.filterIsInstance<yux.compiler.ast.YxService>().map { it.name }
        val fileName = if (baseName in topLevelTypeNames) baseName + "_File" else baseName
        val fileClass = IrClass(
            name = fileName,
            isFileClass = true,
            isData = false,
            isService = false,
            superType = null,
            interfaces = emptyList(),
        )
        module.classes.add(fileClass)
        val clinit = IrMethod(
            name = "\$clinit",
            params = emptyList(),
            returnType = IrType.Void,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = fileClass,
        )
        fileClass.methods.add(clinit)
        bodyGenerators[clinit] = { g -> generateClinit(decls.filterIsInstance<YxProperty>(), g, fileScope) }
        for (decl in decls) {
            when (decl) {
                is YxClass -> registerClass(decl, fileScope, fileClass)
                is YxDataClass -> registerClass(decl, fileScope, fileClass)
                is YxService -> registerClass(decl, fileScope, fileClass)
                is YxFunction -> {
                    val sym = fileScope.topLevelFunctions[decl.name]?.firstOrNull()
                    if (sym != null) registerFunction(decl, sym, fileClass, fileScope)
                }
                is YxProperty -> registerTopLevelProperty(decl, fileClass, fileScope)
                else -> Unit
            }
        }
    }

    private fun registerClass(decl: Any, fileScope: FileScope, fileClass: IrClass) {
        val shape = classShape(decl) ?: return
        val sym = fileScope.types[shape.name] as? YxClassSymbol ?: return
        val irClass = IrClass(
            name = shape.name,
            isFileClass = false,
            isData = sym.isData,
            isService = sym.isService,
            typeParams = sym.typeParams,
            superType = shape.superType?.let { resolveIrType(it, fileScope) },
            interfaces = shape.interfaces.map { resolveIrType(it, fileScope) },
            annotations = buildList {
                shape.annotations.forEach { addAll(toIrAnnotation(it)) }
                // S-5.4.1：service 自动附 @YuxService（源码已标注时去重）
                if (sym.isService && none { it.name == "yux.di.YuxService" }) {
                    add(IrAnnotation("yux.di.YuxService"))
                }
            },
        )
        module.classes.add(irClass)

        val props = sym.properties()
        val propDecls = shape.members.filterIsInstance<YxProperty>()
        for (prop in props) {
            val propDecl = propDecls.firstOrNull { it.name == prop.name }
            if (propDecl != null) registerProperty(prop, propDecl, irClass, fileScope)
        }
        val ctor = IrMethod(
            name = "<init>",
            params = props.map { IrParam(it.name, TypeBridge.toIr(it.type ?: SemaType.ErrorT)) },
            returnType = IrType.Void,
            isStatic = false,
            isConstructor = true,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = irClass,
        )
        irClass.methods.add(ctor)
        val initBlocks = shape.members.filterIsInstance<YxInitBlock>()
        bodyGenerators[ctor] = { g -> generateConstructor(irClass, props, propDecls, initBlocks, g, fileScope) }

        for (fnDecl in shape.members.filterIsInstance<YxFunction>()) {
            val fnSym = sym.functionsNamed(fnDecl.name).firstOrNull()
            if (fnSym != null) registerFunction(fnDecl, fnSym, irClass, fileScope)
        }
    }

    /** 类声明的公共形态（普通类/data/service 的差集提取）。 */
    private data class ClassShape(
        val name: String,
        val superType: YxType?,
        val interfaces: List<YxType>,
        val annotations: List<YxAnnotation>,
        val members: List<YxClassMember>,
    )

    private fun classShape(decl: Any): ClassShape? = when (decl) {
        is YxClass -> ClassShape(decl.name, decl.superType, decl.interfaces, decl.annotations, decl.members)
        is YxDataClass -> ClassShape(decl.name, decl.superType, decl.interfaces, decl.annotations, decl.properties)
        is YxService -> ClassShape(decl.name, null, emptyList(), decl.annotations, decl.members)
        else -> null
    }

    /** AST 注解 → IR 注解（T-M5-6）：限定名转 JVM 名；仅常量字面量实参保留。 */
    private fun toIrAnnotation(a: YxAnnotation): List<IrAnnotation> {
        val name = a.qualifiedName.joinToString(".")
        return listOf(
            IrAnnotation(
                name = BUILTIN_ANNOTATIONS[name] ?: name,
                args = a.args.mapNotNull { arg ->
                    constValue(arg.value)?.let { IrAnnotationArg(arg.name, it) }
                },
            ),
        )
    }

    /** 注解实参常量求值（非字面量返回 null，IRGen 跳过）。 */
    private fun constValue(e: YxExpr): Any? = when (e) {
        is yux.compiler.ast.YxIntLiteral -> Literals.decodeInt(e.text)
        is yux.compiler.ast.YxFloatLiteral -> Literals.decodeFloat(e.text)
        is yux.compiler.ast.YxCharLiteral -> Literals.decodeChar(e.text)
        is yux.compiler.ast.YxBoolLiteral -> e.value
        is yux.compiler.ast.YxNullLiteral -> null
        is yux.compiler.ast.YxStringLiteral -> Literals.decodeString(e.text)
        else -> null
    }

    /** 内置注解简单名 → JVM 限定名（01-§8.2 内置注解；未知注解原样保留）。 */
    private val BUILTIN_ANNOTATIONS = mapOf(
        "Override" to "java.lang.Override",
        "Deprecated" to "java.lang.Deprecated",
        "Serializable" to "yux.serializer.Serializable",
        "YuxService" to "yux.di.YuxService",
    )

    /** 属性 → backing 字段 + getter/setter 骨架（T-M4-1：属性 → 2 方法映射）。 */
    private fun registerProperty(prop: PropertySymbol, propDecl: YxProperty, irClass: IrClass, fileScope: FileScope): IrProperty {
        val type = TypeBridge.toIr(prop.type ?: SemaType.ErrorT)
        // isFinal 与 setter 注册同规则（均以 isVal 为准）；val 声明 set 访问器为 sema 拒绝的程序
        val backingField = IrField(
            name = propDecl.name,
            type = type,
            isStatic = false,
            isFinal = prop.isVal,
            owner = irClass.name,
        )
        propertyFields[prop] = backingField
        val getter = registerAccessor(propDecl, prop, irClass, type, YxAccessorKind.GET, backingField, fileScope)
        val setter = if (prop.isVal) null else registerAccessor(propDecl, prop, irClass, type, YxAccessorKind.SET, backingField, fileScope)
        val irProp = IrProperty(propDecl.name, type, isVal = prop.isVal, isStatic = false, backingField = backingField, getter = getter, setter = setter)
        irClass.fields.add(backingField)
        irClass.properties.add(irProp)
        propertyAccessors[prop] = irProp
        return irProp
    }

    private fun registerAccessor(
        propDecl: YxProperty,
        prop: PropertySymbol,
        irClass: IrClass,
        type: IrType,
        kind: YxAccessorKind,
        backingField: IrField,
        fileScope: FileScope,
    ): IrMethod {
        val method = IrMethod(
            name = if (kind == YxAccessorKind.GET) getterName(propDecl.name, type) else setterName(propDecl.name),
            params = if (kind == YxAccessorKind.SET) listOf(IrParam("value", type)) else emptyList(),
            returnType = if (kind == YxAccessorKind.GET) type else IrType.Void,
            isStatic = false,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = irClass,
        )
        irClass.methods.add(method)
        val custom = propDecl.accessors.firstOrNull { it.kind == kind }
        bodyGenerators[method] = { g ->
            if (custom == null) {
                if (kind == YxAccessorKind.GET) {
                    g.emit(IrStmt.Return(IrExpr.FieldRead(IrExpr.This, backingField)))
                } else {
                    g.emit(IrStmt.FieldAccess(IrExpr.This, backingField, write = true, value = IrExpr.LocalRead(g.lookupLocal("value")!!)))
                }
            } else {
                g.enterScope()
                custom.body.statements.forEach { genStmt(it, g, fileScope) }
                g.exitScope()
            }
        }
        return method
    }

    private fun registerFunction(fnDecl: YxFunction, sym: FunctionSymbol, irClass: IrClass, fileScope: FileScope) {
        // 扩展函数（M9）：receiver 已并入 sym.params（第 0 参数 `this`），编译为文件类静态方法
        val method = IrMethod(
            name = fnDecl.name,
            params = sym.params.map { IrParam(it.name, TypeBridge.toIr(it.type)) },
            returnType = TypeBridge.toIr(sym.returnType ?: SemaType.UnitT),
            isStatic = irClass.isFileClass,
            isConstructor = false,
            isAsync = fnDecl.isAsync,
            isOverride = fnDecl.isOverride,
            isSynthetic = false,
            annotations = fnDecl.annotations.flatMap { toIrAnnotation(it) },
            owner = irClass,
        )
        functionMethods[sym] = method
        irClass.methods.add(method)
        bodyGenerators[method] = { g -> generateFunctionBody(fnDecl, g, fileScope) }
    }

    private fun registerTopLevelProperty(propDecl: YxProperty, fileClass: IrClass, fileScope: FileScope) {
        val sym = fileScope.topLevelProperties[propDecl.name] ?: return
        val type = TypeBridge.toIr(sym.type ?: SemaType.ErrorT)
        val field = IrField(propDecl.name, type, isStatic = true, isFinal = sym.isVal, owner = fileClass.name)
        propertyFields[sym] = field
        val getter = IrMethod(
            name = getterName(propDecl.name, type),
            params = emptyList(),
            returnType = type,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = fileClass,
        )
        val setter = if (sym.isVal) {
            null
        } else {
            IrMethod(
                name = setterName(propDecl.name),
                params = listOf(IrParam("value", type)),
                returnType = IrType.Void,
                isStatic = true,
                isConstructor = false,
                isAsync = false,
                isOverride = false,
                isSynthetic = true,
                owner = fileClass,
            )
        }
        fileClass.fields.add(field)
        val irProp = IrProperty(propDecl.name, type, isVal = sym.isVal, isStatic = true, backingField = field, getter = getter, setter = setter)
        fileClass.properties.add(irProp)
        propertyAccessors[sym] = irProp
        fileClass.methods.add(getter)
        // 自定义访问器优先（S-5.2）：与类属性 registerAccessor 同规则
        val customGetter = propDecl.accessors.firstOrNull { it.kind == YxAccessorKind.GET }
        val customSetter = propDecl.accessors.firstOrNull { it.kind == YxAccessorKind.SET }
        bodyGenerators[getter] = { g ->
            if (customGetter == null) {
                g.emit(IrStmt.Return(IrExpr.FieldRead(null, field)))
            } else {
                g.enterScope()
                customGetter.body.statements.forEach { genStmt(it, g, fileScope) }
                g.exitScope()
            }
        }
        if (setter != null) {
            fileClass.methods.add(setter)
            bodyGenerators[setter] = { g ->
                if (customSetter == null) {
                    g.emit(IrStmt.FieldAccess(null, field, write = true, value = IrExpr.LocalRead(g.lookupLocal("value")!!)))
                } else {
                    g.enterScope()
                    customSetter.body.statements.forEach { genStmt(it, g, fileScope) }
                    g.exitScope()
                }
            }
        }
    }

    // ── 主体遍 ────────────────────────────────────────────────────────────────

    private fun generateFunctionBody(fnDecl: YxFunction, g: MethodGen, fileScope: FileScope) {
        // 扩展函数（M9）：`this` 读 receiver 局部（第 0 参数，MethodGen 已按参数名登记）
        val saved = extensionReceiverName
        extensionReceiverName = if (fnDecl.receiver != null) "this" else null
        when (val body = fnDecl.body) {
            is yux.compiler.ast.YxFunctionBody.YxExpressionBody ->
                g.emit(IrStmt.Return(genExpr(body.expr, g, fileScope)))
            is yux.compiler.ast.YxFunctionBody.YxBlockBody -> {
                g.enterScope()
                body.block.statements.forEach { genStmt(it, g, fileScope) }
                g.exitScope()
            }
        }
        extensionReceiverName = saved
    }

    private fun generateConstructor(
        irClass: IrClass,
        props: List<PropertySymbol>,
        propDecls: List<YxProperty>,
        initBlocks: List<YxInitBlock>,
        g: MethodGen,
        fileScope: FileScope,
    ) {
        // S-5.2.5：带初始化器的属性先执行初始化表达式（构造参数赋值之后覆盖）
        props.forEach { prop ->
            val decl = propDecls.firstOrNull { it.name == prop.name } ?: return@forEach
            val init = decl.initializer ?: return@forEach
            val field = propertyFields[prop] ?: return@forEach
            g.emit(IrStmt.FieldAccess(IrExpr.This, field, write = true, value = genExpr(init, g, fileScope)))
        }
        // 构造参数赋值（覆盖初始化器；S-5.1.3 全参构造器）
        props.forEachIndexed { i, prop ->
            val field = propertyFields[prop] ?: return@forEachIndexed
            g.emit(IrStmt.FieldAccess(IrExpr.This, field, write = true, value = IrExpr.LocalRead(g.paramLocals[i])))
        }
        g.enterScope()
        initBlocks.forEach { block -> block.body.statements.forEach { genStmt(it, g, fileScope) } }
        g.exitScope()
    }

    private fun generateClinit(props: List<YxProperty>, g: MethodGen, fileScope: FileScope) {
        g.enterScope()
        for (propDecl in props) {
            val init = propDecl.initializer ?: continue
            val sym = fileScope.topLevelProperties[propDecl.name] ?: continue
            val field = propertyFields[sym] ?: continue
            g.emit(IrStmt.FieldAccess(null, field, write = true, value = genExpr(init, g, fileScope)))
        }
        g.exitScope()
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** 文件类名：`main.yux` → `Main`（05-§5.3 文件类；兼容 Windows 分隔符）。 */
    private fun fileClassName(path: String): String {
        val stem = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return stem.replaceFirstChar { it.uppercaseChar() }
    }

    private fun getterName(propName: String, type: IrType): String {
        val cap = propName.replaceFirstChar { it.uppercaseChar() }
        val bool = type is IrType.Basic && type.name == "Boolean"
        return if (bool) "is$cap" else "get$cap"
    }

    private fun setterName(propName: String): String = "set${propName.replaceFirstChar { it.uppercaseChar() }}"

    /** AST 类型 → IrType（经 TypeResolver；仅供 YxIs/YxAs/catch 使用）。 */
    fun resolveIrType(type: YxType, fileScope: FileScope): IrType =
        TypeBridge.toIr(typeResolver.resolve(type, fileScope) ?: SemaType.ErrorT)

    /** 生成 Lambda 合成方法并返回其引用（ExprGen 回调）。 */
    fun newLambdaMethod(
        name: String,
        params: List<IrParam>,
        returnType: IrType,
        isStatic: Boolean,
        owner: IrClass,
    ): IrMethod {
        val method = IrMethod(
            name = name,
            params = params,
            returnType = returnType,
            isStatic = isStatic,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = owner,
        )
        owner.methods.add(method)
        return method
    }

    fun nextLambdaName(): String = "lambda\$${lambdaCounter++}"

    /** 进入 StmtGen（单向入口；语句直接发射到当前发射目标）。 */
    fun genStmt(stmt: yux.compiler.ast.YxStmt, g: MethodGen, fileScope: FileScope): Unit =
        StmtGen(this).gen(stmt, g, fileScope)

    fun genExpr(expr: yux.compiler.ast.YxExpr, g: MethodGen, fileScope: FileScope): IrExpr =
        ExprGen(this, resolver).gen(expr, g, fileScope)
}

/**
 * 方法生成上下文（T-M4-4）：局部槽位分配、作用域栈、循环标签栈、标签计数。
 * 每个方法（含 Lambda 合成方法）独立持有。
 *
 * 发射目标 [emitTarget] 可临时切换（Try/catch/finally 子块生成时指向子列表），
 * 保证表达式级发射（短路、表达式位置赋值）落在正确的语句列表中。
 */
class MethodGen(
    val method: IrMethod,
    val owner: IrClass,
) {
    private val scopes = ArrayDeque<MutableMap<String, IrLocal>>()
    private val loops = ArrayDeque<Pair<IrLabel, IrLabel>>()
    private var labelCounter = 0
    private var localIndex = method.params.size
    private var emitTarget: MutableList<IrStmt> = method.body

    init {
        val root = mutableMapOf<String, IrLocal>()
        method.paramLocals.forEach { root[it.name] = it }
        scopes.addLast(root)
    }

    val paramLocals: List<IrLocal> get() = method.paramLocals

    fun newLabel(): IrLabel = IrLabel("L${labelCounter++}")

    fun newLocal(name: String, type: IrType): IrLocal = IrLocal(name, type, localIndex++)

    fun enterScope() {
        scopes.addLast(mutableMapOf())
    }

    fun exitScope() {
        scopes.removeLast()
    }

    fun declareLocal(name: String, local: IrLocal) {
        scopes.last()[name] = local
    }

    fun lookupLocal(name: String): IrLocal? {
        for (i in scopes.indices.reversed()) {
            scopes[i][name]?.let { return it }
        }
        return null
    }

    fun pushLoop(continueLabel: IrLabel, breakLabel: IrLabel) {
        loops.addLast(continueLabel to breakLabel)
    }

    fun popLoop() {
        loops.removeLast()
    }

    val currentLoop: Pair<IrLabel, IrLabel>? get() = loops.lastOrNull()

    /** 发射到当前目标（默认方法体；子块生成时切到子列表）。 */
    fun emit(stmt: IrStmt) {
        emitTarget.add(stmt)
    }

    /** 临时切换发射目标（Try/catch/finally 子块），块结束后恢复。 */
    fun <T> withTarget(target: MutableList<IrStmt>, block: () -> T): T {
        val prev = emitTarget
        emitTarget = target
        try {
            return block()
        } finally {
            emitTarget = prev
        }
    }
}
