package yux.compiler.sema

import yux.compiler.ast.SourceSpan
import yux.compiler.ast.YxAccessor
import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAsyncBlock
import yux.compiler.ast.YxAwait
import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxBreak
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxCharLiteral
import yux.compiler.ast.YxContinue
import yux.compiler.ast.YxElseCondition
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxExprCondition
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxFloatLiteral
import yux.compiler.ast.YxFor
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIf
import yux.compiler.ast.YxIfExpr
import yux.compiler.ast.YxIndexExpr
import yux.compiler.ast.YxInitBlock
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxIsCondition
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNode
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxParallelBlock
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxRange
import yux.compiler.ast.YxRawStringLiteral
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxStmt
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxStringTemplate
import yux.compiler.ast.YxStringText
import yux.compiler.ast.YxSuper
import yux.compiler.ast.YxThis
import yux.compiler.ast.YxThrow
import yux.compiler.ast.YxTry
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxTypeReference
import yux.compiler.ast.YxUnary
import yux.compiler.ast.YxUnsafeBlock
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxVisibility
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhenCondition
import yux.compiler.ast.YxWhenExpr
import yux.compiler.ast.YxWhenExprBranch
import yux.compiler.ast.YxWhile
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
import yux.compiler.lexer.SourcePosition
import yux.compiler.lowering.GuardPoint

/**
 * 类型检查器（T-M3-4 / 02-§7.1）：遍历 AST 推导每个表达式的 [SemaType]，
 * 执行 S-xx 规则（条件 Boolean、参数匹配、确定性赋值、泛型元数、运算符合法性、
 * 返回类型、override 调用、空守卫接入、智能转型）。
 */
class TypeChecker(
    private val symbolTable: SymbolTable,
    private val classPath: ClassPathSymbolProvider,
    private val typeResolver: TypeResolver,
    private val inference: Inference,
    private val diagnostics: DiagnosticSink,
) {
    private val exprTypes = mutableMapOf<YxExpr, SemaType>()
    private val declTypes = mutableMapOf<yux.compiler.ast.YxDecl, SemaType>()
    private val resolvedRefs = mutableMapOf<YxNode, Symbol>()
    private val smartCast = SmartCast()
    private val nullGuard = yux.compiler.lowering.NullGuard(diagnostics)

    private val varStack = ArrayDeque<MutableMap<String, VariableSymbol>>()
    private var currentFile: FileScope? = null
    private var currentClass: YxClassSymbol? = null
    private var currentReturnType: SemaType? = null
    private var currentReceiver: SemaType? = null
    private var loopDepth = 0

    /**
     * async 上下文深度（T-M14）：`async fun` 体 / `async { }` 块体内为真——
     * 该上下文内 `await` 合法、async fun 调用为挂起调用（返回声明类型）；
     * 普通 lambda 体暂存并清零（lambda 不能挂起）。
     */
    private var asyncContextDepth = 0

    private fun isInAsyncContext(): Boolean = asyncContextDepth > 0

    /** 在 async 上下文内执行 [block]。 */
    private fun <T> withAsyncContext(block: () -> T): T {
        asyncContextDepth++
        try {
            return block()
        } finally {
            asyncContextDepth--
        }
    }

    /** `yux.async.Task` 类型（同步上下文 async fun 调用的返回类型，S-8.4）。 */
    private val taskType: SemaType by lazy {
        classPath.resolve("yux.async.Task")?.let { SemaType.Declared(it, emptyList(), nullable = false) }
            ?: SemaType.ErrorT
    }

    /** 表达式/声明结果的读取接口（供 [SemanticAnalyzer] 收集）。 */
    val exprTypesView: Map<YxExpr, SemaType> get() = exprTypes
    val declTypesView: Map<yux.compiler.ast.YxDecl, SemaType> get() = declTypes
    val resolvedRefsView: Map<YxNode, Symbol> get() = resolvedRefs
    val guardPoints: List<GuardPoint> get() = nullGuard.guardPoints

    fun checkAll(declsByFile: Map<String, List<yux.compiler.ast.YxDecl>>) {
        for (file in symbolTable.files) {
            currentFile = file
            for (sym in file.types.values.filterIsInstance<YxClassSymbol>()) {
                checkClass(file, sym)
            }
            // 顶层属性先于函数检查（M9）：函数体内引用顶层属性需要类型已推断
            for (prop in file.topLevelProperties.values) {
                prop.decl?.let { checkTopLevelProperty(file, prop, it) }
            }
            for (fns in file.topLevelFunctions.values) {
                for (fn in fns) fn.decl?.let { checkFunction(file, fn, it) }
            }
            currentFile = null
        }
    }

    // ── 声明 ────────────────────────────────────────────────────────────────

    private fun checkClass(
        file: FileScope,
        sym: YxClassSymbol,
    ) {
        val saved = currentClass
        currentClass = sym
        for (prop in sym.properties()) {
            prop.decl?.let { checkProperty(file, sym, prop, it) }
        }
        for (init in classInitBlocks(file, sym)) {
            checkStatements(init)
        }
        for (fn in sym.functions()) {
            fn.decl?.let { checkFunction(file, fn, it) }
        }
        currentClass = saved
    }

    /** 类初始化块（S-5.2.5：构造时先执行属性初始化，再执行初始化块）。 */
    private fun classInitBlocks(
        file: FileScope,
        sym: YxClassSymbol,
    ): List<YxBlock> {
        val decls = file.decls
        val initBlocks = mutableListOf<YxBlock>()

        fun collect(members: List<Any>) {
            for (m in members) {
                if (m is YxInitBlock) initBlocks += m.body
            }
        }
        for (d in decls) {
            when (d) {
                is yux.compiler.ast.YxClass -> if (d.name == sym.name) collect(d.members)
                is yux.compiler.ast.YxService -> if (d.name == sym.name) collect(d.members)
                else -> Unit
            }
        }
        return initBlocks
    }

    private fun checkProperty(
        file: FileScope,
        owner: YxClassSymbol,
        prop: PropertySymbol,
        decl: YxProperty,
    ) {
        val declared = prop.type
        val initType = decl.initializer?.let { typeOf(it, expected = declared) }
        if (declared != null && initType != null && !initType.isError) {
            inference.expectAssignable(initType, declared, decl.span.start, "属性 '${decl.name}' 初始化")
        } else if (declared == null) {
            if (initType == null) {
                diagnostics.error(
                    "属性 '${decl.name}' 缺少类型且缺少初始化表达式（无法推断）",
                    decl.span.start,
                    ErrorCodes.INFERENCE_FAILURE,
                )
                prop.type = SemaType.ErrorT
            } else {
                prop.type = initType
            }
        }
        for (accessor in decl.accessors) {
            checkAccessor(file, prop.type ?: SemaType.ErrorT, accessor)
        }
        declTypes[decl] = prop.type ?: SemaType.ErrorT
    }

    private fun checkAccessor(
        file: FileScope,
        propertyType: SemaType,
        accessor: YxAccessor,
    ) {
        enterFunctionScope()
        val savedReturn = currentReturnType
        when (accessor.kind) {
            YxAccessorKind.GET -> {
                // S-5.2.3：`value` 指代 backing field（只读）
                varStack.last()["value"] = VariableSymbol("value", propertyType, isVal = true, null).also { it.definitelyAssigned = true }
                currentReturnType = propertyType
                checkStatements(accessor.body)
                val last = accessor.body.statements.lastOrNull()
                val lastValue = (last as? YxReturn)?.value
                val ret: SemaType =
                    when {
                        last is YxExprStmt -> typeOf(last.expr)
                        lastValue != null -> typeOf(lastValue)
                        else -> SemaType.UnitT
                    }
                if (!ret.isError) {
                    inference.expectReturn(ret, propertyType, accessor.span.start, "getter 返回")
                }
            }

            YxAccessorKind.SET -> {
                // S-5.2.3：`value` 指代 backing field（可写）；`set` 指代新值（只读）
                varStack.last()["value"] = VariableSymbol("value", propertyType, isVal = false, null).also { it.definitelyAssigned = true }
                varStack.last()["set"] = VariableSymbol("set", propertyType, isVal = true, null).also { it.definitelyAssigned = true }
                currentReturnType = SemaType.UnitT
                checkStatements(accessor.body)
            }
        }
        currentReturnType = savedReturn
        leaveFunctionScope()
    }

    private fun checkTopLevelProperty(
        file: FileScope,
        prop: PropertySymbol,
        decl: YxProperty,
    ) {
        val declared = prop.type
        val initType = decl.initializer?.let { typeOf(it, expected = declared) }
        if (declared != null && initType != null && !initType.isError) {
            inference.expectAssignable(initType, declared, decl.span.start, "顶层属性 '${decl.name}'")
        } else if (declared == null) {
            if (initType == null) {
                diagnostics.error(
                    "顶层属性 '${decl.name}' 缺少类型且缺少初始化表达式",
                    decl.span.start,
                    ErrorCodes.INFERENCE_FAILURE,
                )
            } else {
                prop.type = initType
            }
        }
        for (accessor in decl.accessors) {
            checkAccessor(file, prop.type ?: SemaType.ErrorT, accessor)
        }
        declTypes[decl] = prop.type ?: SemaType.ErrorT
    }

    private fun checkFunction(
        file: FileScope,
        fn: FunctionSymbol,
        decl: YxFunction,
    ) {
        // 预扫赋值目标：决定智能转型是否允许（02-§7.3）
        val reassigned = AstScan.assignmentTargetNames(decl.body)
        enterFunctionScope(reassigned)
        fn.params.forEach { p ->
            varStack.last()[p.name] = VariableSymbol(p.name, p.type, isVal = true, p.span).also { it.definitelyAssigned = true }
        }
        // S-5.5.5 参数默认值：在函数作用域内类型检查 `= Expression`（T-M12 缺陷修复，
        // 默认值表达式此前从未被分析，导致类型不匹配被静默接受且 IRGen 无法生成）。
        val recvOffset = if (fn.receiverType != null) 1 else 0
        decl.params.forEachIndexed { i, p ->
            p.defaultValue?.let { def ->
                val param = fn.params.getOrNull(i + recvOffset)
                if (param != null) {
                    val t = typeOf(def, expected = param.type)
                    if (!t.isError) {
                        inference.expectAssignable(t, param.type, def.span.start, "参数 '${p.name}' 默认值")
                    }
                }
            }
        }
        // 返回类型：未声明 → 推断变量（S-5.5.2），块体无返回 → Unit
        val declaredReturn = fn.returnType ?: inference.freshVar().also { fn.returnType = it }
        currentReturnType = declaredReturn
        // 扩展函数（M9）：receiver 作为隐式 this（体内 `this.xxx` 访问）
        val savedReceiver = currentReceiver
        currentReceiver = fn.receiverType
        val bodyType: SemaType? =
            when (val body = decl.body) {
                is YxFunctionBody.YxExpressionBody -> {
                    // async fun 表达式体（`async fun f() = await x`）为 async 上下文（T-M14）
                    val t =
                        if (fn.isAsync) {
                            withAsyncContext {
                                typeOf(
                                    body.expr,
                                    expected = declaredReturn,
                                )
                            }
                        } else {
                            typeOf(body.expr, expected = declaredReturn)
                        }
                    inference.expectReturn(t, declaredReturn, body.span.start, "函数 '${fn.name}'")
                }

                is YxFunctionBody.YxBlockBody -> {
                    // async fun 块体为 async 上下文（await 合法、async fun 调用为挂起调用）
                    if (fn.isAsync) withAsyncContext { checkStatements(body.block) } else checkStatements(body.block)
                    // S-5.5.2：声明非 Unit 返回的块体必须包含 return
                    if (declaredReturn !is SemaType.InferenceVar &&
                        !TypeAssignability.sameBase(declaredReturn, SemaType.UnitT) &&
                        !blockHasReturn(body.block)
                    ) {
                        diagnostics.error(
                            "函数 '${fn.name}' 声明返回 ${declaredReturn.render()} 但缺少 return 语句",
                            decl.span.start,
                            ErrorCodes.RETURN_TYPE_MISMATCH,
                        )
                    }
                    null
                }
            }
        // 返回类型求解：表达式体取表达式类型（函数体是推断返回类型的唯一权威——缺陷修复：
        // 调用点约束（如 `println` 的 Any 参数）不再能覆盖由函数体解出的类型，见 Inference.checkAssignable；
        // 块体未显式返回 → Unit（S-5.5.2））
        if (declaredReturn is SemaType.InferenceVar) {
            if (bodyType != null) {
                declaredReturn.solution = bodyType
            } else if (declaredReturn.solution == null) {
                declaredReturn.solution = SemaType.UnitT
            }
        }
        declTypes[decl] = (declaredReturn as? SemaType.InferenceVar)?.solution ?: declaredReturn
        currentReceiver = savedReceiver
        leaveFunctionScope()
    }

    /** 进入函数作用域：新建变量层 + 重置智能转型。 */
    private fun enterFunctionScope(reassigned: Set<String> = emptySet()) {
        smartCast.restore(emptyMap())
        varStack.addLast(mutableMapOf())
        pendingReassigned = reassigned
    }

    private fun leaveFunctionScope() {
        varStack.removeLast()
        pendingReassigned = emptySet()
    }

    private var pendingReassigned: Set<String> = emptySet()

    private fun isReassigned(name: String): Boolean = name in pendingReassigned

    // ── 语句 ────────────────────────────────────────────────────────────────

    private fun checkStatements(stmts: List<YxStmt>) {
        stmts.forEach { checkStatement(it) }
    }

    private fun checkStatements(block: YxBlock) = checkStatements(block.statements)

    private fun checkStatement(stmt: YxStmt) {
        when (stmt) {
            is YxBlock -> {
                smartCast.enterBlock()
                varStack.addLast(mutableMapOf())
                checkStatements(stmt.statements)
                varStack.removeLast()
                smartCast.exitBlock()
            }

            is YxVarDecl -> {
                checkVarDecl(stmt)
            }

            is YxAssign -> {
                checkAssign(stmt)
            }

            is YxExprStmt -> {
                typeOf(stmt.expr)
            }

            is YxIf -> {
                checkIf(stmt)
            }

            is YxWhen -> {
                checkWhen(stmt)
            }

            is YxFor -> {
                checkFor(stmt)
            }

            is YxWhile -> {
                checkWhile(stmt)
            }

            is YxReturn -> {
                checkReturn(stmt)
            }

            is YxBreak -> {
                if (loopDepth == 0) {
                    diagnostics.error("break 在循环外（S-6.4.3）", stmt.span.start, ErrorCodes.BREAK_OUTSIDE_LOOP)
                }
            }

            is YxContinue -> {
                if (loopDepth == 0) {
                    diagnostics.error("continue 在循环外（S-6.4.3）", stmt.span.start, ErrorCodes.BREAK_OUTSIDE_LOOP)
                }
            }

            is YxTry -> {
                checkTry(stmt)
            }

            is YxAsyncBlock -> {
                withAsyncContext { checkStatements(stmt.body) }
            }

            is YxParallelBlock -> {
                checkStatements(stmt.body)
            }

            is YxUnsafeBlock -> {
                checkStatements(stmt.body)
            }
        }
    }

    private fun checkVarDecl(stmt: YxVarDecl) {
        val existing = lookupVariable(stmt.name)
        if (existing != null) {
            // S-6.1.1：已在作用域链声明 → 赋值语义
            existing.reassigned = true
            if (existing.isVal) {
                // val（参数等）被重声明视为 val 赋值，与 checkAssign 的 E0020 一致（S-6.1.1）
                diagnostics.error(
                    "只读变量 '${stmt.name}' 不可赋值（S-6.1.1）",
                    stmt.span.start,
                    ErrorCodes.VAL_ASSIGNMENT,
                )
            }
            val valueType = stmt.initializer?.let { typeOf(it, expected = existing.type) } ?: existing.type ?: SemaType.ErrorT
            if (!valueType.isError && existing.type != null) {
                inference.expectAssignable(valueType, existing.type!!, stmt.span.start, "变量 '${stmt.name}'")
            }
            if (stmt.initializer != null) existing.definitelyAssigned = true
            return
        }
        if (stmt.type == null && stmt.initializer == null) {
            diagnostics.error(
                "变量 '${stmt.name}' 缺少类型且缺少初始化表达式",
                stmt.span.start,
                ErrorCodes.INFERENCE_FAILURE,
            )
            varStack.last()[stmt.name] = VariableSymbol(stmt.name, SemaType.ErrorT, isVal = false, stmt.span)
            return
        }
        val declared = stmt.type?.let { typeResolver.resolve(it, currentFile!!) }
        val initType = stmt.initializer?.let { typeOf(it, expected = declared) }
        if (declared != null && initType != null && !initType.isError) {
            inference.expectAssignable(initType, declared, stmt.span.start, "变量 '${stmt.name}'")
        }
        val type = declared ?: initType ?: SemaType.ErrorT
        val sym = VariableSymbol(stmt.name, type, isVal = false, stmt.span)
        sym.definitelyAssigned = stmt.initializer != null
        varStack.last()[stmt.name] = sym
    }

    private fun checkAssign(stmt: YxAssign) {
        // `..=` 复合赋值（区间赋值）未实现：sema 直接报错，不放行到 IRGen（缺陷 7）
        if (stmt.op == "..=") {
            diagnostics.error(
                "`..=` 复合赋值不支持（v0.1）",
                stmt.span.start,
                ErrorCodes.INVALID_OPERATOR_OPERAND,
            )
            typeOf(stmt.value)
            return
        }
        // 复合赋值（`+=` 等）：按 `x = x op e` 展开检查（S-7.5）——右操作数先以目标类型为
        // 期望收窄字面量（S-7.5.4，`f += 2.5` 不得提升为 Double），再做二元运算类型检查
        // （数字提升 / String 拼接，S-7.5.1/S-7.6.1），结果类型须可赋值回目标。
        // `s += 1`（String 拼接）合法；`x += true`（Int+Boolean）报 INVALID_OPERATOR_OPERAND。
        val targetType = expectedAssignTargetType(stmt.target)
        val valueType =
            if (stmt.op != "=" && targetType != null) {
                val narrowed = typeOf(stmt.value, expected = targetType)
                binaryOpResult(stmt.op.dropLast(1), targetType, narrowed, stmt.span)
            } else {
                typeOf(stmt.value, expected = targetType)
            }
        when (val target = stmt.target) {
            is YxIdentifier -> {
                val local = lookupVariable(target.name)
                if (local != null) {
                    local.reassigned = true
                    if (local.isVal) {
                        diagnostics.error("只读变量 '${target.name}' 不可赋值（S-6.1.1）", stmt.span.start, ErrorCodes.VAL_ASSIGNMENT)
                    }
                    if (local.type != null && !valueType.isError) {
                        inference.expectAssignable(valueType, local.type!!, stmt.span.start, "赋值 '${target.name}'")
                    }
                    local.definitelyAssigned = true
                    resolvedRefs[target] = local
                    return
                }
                // 顶层属性赋值（M9）：文件级静态字段可写
                val prop = currentFile?.topLevelProperties?.get(target.name)
                if (prop == null) {
                    diagnostics.error("未解析的变量 '${target.name}'", stmt.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                    return
                }
                if (prop.isVal) {
                    diagnostics.error("只读变量 '${target.name}' 不可赋值（S-6.1.1）", stmt.span.start, ErrorCodes.VAL_ASSIGNMENT)
                }
                if (prop.type != null && !valueType.isError) {
                    inference.expectAssignable(valueType, prop.type!!, stmt.span.start, "赋值 '${target.name}'")
                }
                resolvedRefs[target] = prop
            }

            is YxMemberAccess -> {
                val receiverType = typeOf(target.receiver)
                if (receiverType.isError) return
                if (target.receiver is YxTypeReference) {
                    diagnostics.error("静态成员不可赋值", stmt.span.start, ErrorCodes.VAL_ASSIGNMENT)
                    return
                }
                nullGuard.reportWriteOnNullable(target, receiverType)
                val writable = memberWritability(receiverType, target.name)
                // 访问控制（S-5.1.4，缺陷 10）
                val ownerClass = (receiverType as? SemaType.Declared)?.symbol as? YxClassSymbol
                ownerClass?.propertyIncludingSuper(target.name)?.let { prop ->
                    prop.owner?.let { checkMemberAccess(it, prop.visibility, target.span, "成员 '${target.name}'") }
                }
                if (writable == null) {
                    diagnostics.error(
                        "成员 '${target.name}' 不存在于类型 '${receiverType.render()}'",
                        target.span.start,
                        ErrorCodes.UNRESOLVED_MEMBER,
                    )
                } else if (writable === false) {
                    diagnostics.error(
                        "只读成员 '${target.name}' 不可赋值（S-5.2.2）",
                        target.span.start,
                        ErrorCodes.VAL_ASSIGNMENT,
                    )
                } else if (!valueType.isError) {
                    inference.expectAssignable(valueType, writable as SemaType, stmt.span.start, "赋值 '${target.name}'")
                }
            }

            is YxIndexExpr -> {
                val elemType = typeOfIndexExpr(target)
                typeOf(target.index)
                if (!elemType.isError && !valueType.isError) {
                    inference.expectAssignable(valueType, elemType, stmt.span.start, "索引赋值 '${target.base}'")
                }
            }

            else -> {
                diagnostics.error("赋值目标不合法（S-6.1.1）", stmt.span.start, ErrorCodes.ILLEGAL_ASSIGN_TARGET)
            }
        }
    }

    /** 赋值目标的期望类型（供字面量/null 收窄，S-7.5.4/S-8.1）。 */
    private fun expectedAssignTargetType(target: YxExpr): SemaType? =
        when (target) {
            is YxIdentifier -> lookupVariable(target.name)?.type
            is YxMemberAccess -> memberLookup(typeOf(target.receiver), target.name)?.let { (t, _, _) -> t }
            is YxIndexExpr -> typeOfIndexExpr(target)
            else -> null
        }

    /** 成员可写性：null=不存在；false=只读；SemaType=可写且目标类型。 */
    private fun memberWritability(
        receiver: SemaType,
        name: String,
    ): Any? =
        when (receiver) {
            is SemaType.Declared -> {
                val sym = receiver.symbol
                when (sym) {
                    is YxClassSymbol -> {
                        val p = sym.propertyIncludingSuper(name) ?: return null
                        if (p.isVal) false else p.type ?: SemaType.ErrorT
                    }

                    is JvmClassSymbol -> {
                        if (sym.propertyType(name) == null) {
                            null
                        } else if (!sym.propertySettable(name)) {
                            false
                        } else {
                            sym.propertyType(name)
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

            is SemaType.Basic -> {
                if (jvmForBasic(receiver.name) == null) {
                    null
                } else {
                    memberWritability(jvmForBasic(receiver.name)!!.let { SemaType.Declared(it, emptyList(), receiver.nullable) }, name)
                }
            }

            else -> {
                null
            }
        }

    private fun checkIf(stmt: YxIf) {
        val condType = typeOf(stmt.condition)
        if (!condType.isError && !TypeAssignability.sameBase(condType, SemaType.BOOLEAN)) {
            diagnostics.error(
                "if 条件必须是 Boolean（S-6.2.1），实际 ${condType.render()}",
                stmt.condition.span.start,
                ErrorCodes.CONDITION_NOT_BOOLEAN,
            )
        }
        val base = smartCast.snapshot()
        val assignedBefore = definiteAssignmentSnapshot()
        // then 分支
        smartCast.restore(base)
        applyConditionCasts(stmt.condition, thenBranch = true)
        checkStatement(stmt.thenBranch)
        val thenCasts = smartCast.snapshot()
        val thenAssigned = definiteAssignmentSnapshot()
        // else 分支（从 before 状态开始，避免 then 分支污染快照）
        smartCast.restore(base)
        restoreDefiniteAssignment(assignedBefore)
        if (stmt.elseBranch != null) {
            applyConditionCasts(stmt.condition, thenBranch = false)
            checkStatement(stmt.elseBranch)
        }
        val elseCasts = smartCast.snapshot()
        val elseAssigned = definiteAssignmentSnapshot()
        // 合并转型：仅保留两分支共同成立的转型（02-§7.3）
        smartCast.restore(base)
        for ((name, t) in thenCasts) {
            if (name in elseCasts) smartCast.register(name, t)
        }
        // 合并确定性赋值（S-6.1.2）：两分支均赋值才视为已赋值
        mergeDefiniteAssignment(assignedBefore, thenAssigned, elseAssigned, hasElse = stmt.elseBranch != null)
    }

    /** 快照当前可见变量的确定性赋值状态（S-6.1.2）。 */
    private fun definiteAssignmentSnapshot(): Map<String, Boolean> {
        val visible = mutableMapOf<String, Boolean>()
        for (i in varStack.indices.reversed()) {
            for ((name, sym) in varStack[i]) {
                visible[name] = sym.definitelyAssigned
            }
        }
        return visible
    }

    /** 分支合并：两分支均赋值 → 已赋值；无 else 分支时按「未保证」处理。 */
    private fun mergeDefiniteAssignment(
        before: Map<String, Boolean>,
        thenS: Map<String, Boolean>,
        elseS: Map<String, Boolean>,
        hasElse: Boolean,
    ) {
        for (i in varStack.indices.reversed()) {
            for ((name, sym) in varStack[i]) {
                val b = before[name] ?: false
                val inThen = thenS[name] ?: b
                val inElse = if (hasElse) (elseS[name] ?: b) else b
                sym.definitelyAssigned = b || (inThen && inElse)
            }
        }
    }

    private fun applyConditionCasts(
        condition: YxExpr,
        thenBranch: Boolean,
    ) {
        when (condition) {
            is YxParen -> {
                applyConditionCasts(condition.expr, thenBranch)
            }

            is YxIs -> {
                val name = (condition.expr as? YxIdentifier)?.name ?: return
                val declared = lookupVariable(name)?.type ?: return
                val resolved = typeResolver.resolve(condition.type, currentFile!!)
                if (resolved.isError) return
                // S-6.3.1：声明类型与目标类型不可能有交集 → 报错
                if (!TypeAssignability.possiblySame(declared, resolved)) {
                    diagnostics.error(
                        "类型检查不可能成立: '${declared.render()}' 与 '${resolved.render()}' 无交集（S-6.3.1）",
                        condition.span.start,
                        ErrorCodes.TYPE_MISMATCH,
                    )
                    return
                }
                // else 分支：x is !T（可空声明类型下即 null），无可表示的反向转型——不注册
                if (!thenBranch) return
                if (!smartCast.applyIsCast(name, resolved, ::isReassigned)) {
                    diagnostics.remind(
                        "可变引用 '$name' 的智能转型被抑制（02-§7.3）",
                        condition.span.start,
                        ErrorCodes.SMART_CAST_MUTABLE,
                    )
                }
            }

            is YxBinary -> {
                when (condition.op) {
                    // `&&` 链：then 分支左右均为真 → 先应用左侧转型，右侧在左侧转型生效下检查
                    "&&" -> {
                        if (thenBranch) {
                            applyConditionCasts(condition.left, true)
                            applyConditionCasts(condition.right, true)
                        }
                    }

                    // `||` 链：else 分支左右均为假 → 先应用左侧反向转型，再应用右侧反向转型
                    "||" -> {
                        if (!thenBranch) {
                            applyConditionCasts(condition.left, false)
                            applyConditionCasts(condition.right, false)
                        }
                    }

                    else -> {
                        applyNullConditionCast(condition, thenBranch)
                    }
                }
            }

            else -> {
                applyNullConditionCast(condition, thenBranch)
            }
        }
    }

    private fun applyNullConditionCast(
        condition: YxExpr,
        thenBranch: Boolean,
    ) {
        smartCast.applyNullCondition(
            condition,
            thenBranch,
            declaredType = { lookupVariable(it)?.type },
            isReassigned = ::isReassigned,
            onMutableCast = { name ->
                diagnostics.remind(
                    "可变引用 '$name' 的智能转型被抑制（02-§7.3）",
                    condition.span.start,
                    ErrorCodes.SMART_CAST_MUTABLE,
                )
            },
        )
    }

    private fun checkWhen(stmt: YxWhen) {
        val subject = stmt.subject
        val subjectType = subject?.let { typeOf(it) }
        for (branch in stmt.branches) {
            smartCast.enterBlock()
            checkWhenCondition(branch.condition, subject, subjectType)
            checkStatement(branch.body)
            smartCast.exitBlock()
        }
        // T-M12：密封 subject 穷尽性检查（语句与表达式共用，非密封语句允许无 else 直落，S-6.3.2）
        checkWhenExhaustiveness(stmt.span.start, stmt.branches.map { it.condition }, subjectType, isExpression = false)
    }

    /**
     * when 分支条件检查（subject 可有可无，S-6.3）：
     * - 有 subject：is 分支做智能转型、表达式条件与 subject 匹配；
     * - 无 subject（`when { cond -> }`）：is 分支非法，表达式条件须为 Boolean。
     */
    private fun checkWhenCondition(
        cond: YxWhenCondition,
        subject: YxExpr?,
        subjectType: SemaType?,
    ) {
        when (cond) {
            is YxIsCondition -> {
                if (subject == null || subjectType == null) {
                    diagnostics.error(
                        "无 subject 的 when 不能使用 is 分支（is 需要 subject 作转型目标，S-6.3.1）",
                        cond.span.start,
                        ErrorCodes.TYPE_MISMATCH,
                    )
                    return
                }
                val resolved = typeResolver.resolve(cond.type, currentFile!!)
                val name = subjectAsVariable(subject)
                val declared = name?.let { lookupVariable(it)?.type }
                if (name != null && !resolved.isError && declared != null && !TypeAssignability.possiblySame(declared, resolved)) {
                    diagnostics.error(
                        "类型检查不可能成立: '${declared.render()}' 与 '${resolved.render()}' 无交集（S-6.3.1）",
                        cond.span.start,
                        ErrorCodes.TYPE_MISMATCH,
                    )
                } else if (name != null && !resolved.isError) {
                    if (!smartCast.applyIsCast(name, resolved, ::isReassigned)) {
                        diagnostics.remind("可变引用 '$name' 的智能转型被抑制", cond.span.start, ErrorCodes.SMART_CAST_MUTABLE)
                    }
                }
            }

            is YxExprCondition -> {
                if (subjectType == null) {
                    // 无 subject：条件为布尔表达式（Kotlin 同款）
                    val condType = typeOf(cond.expr)
                    if (!condType.isError && !TypeAssignability.sameBase(condType, SemaType.BOOLEAN)) {
                        diagnostics.error(
                            "无 subject 的 when 分支条件必须是 Boolean（S-6.3），实际 ${condType.render()}",
                            cond.span.start,
                            ErrorCodes.CONDITION_NOT_BOOLEAN,
                        )
                    }
                } else {
                    // when 分支条件与 subject 匹配（S-6.3），null 字面量以 subject 为期望
                    val condType = typeOf(cond.expr, expected = subjectType)
                    if (!condType.isError && !subjectType.isError && !TypeAssignability.sameBase(condType, subjectType)) {
                        diagnostics.error(
                            "when 分支条件与 subject 类型不匹配: '${condType.render()}' 与 '${subjectType.render()}'",
                            cond.span.start,
                            ErrorCodes.TYPE_MISMATCH,
                        )
                    }
                }
            }

            is YxElseCondition -> {
                // else 分支：subject == null 时 subject 为非空
                if (subjectType != null && subjectType.nullable) {
                    val name = subject?.let { subjectAsVariable(it) }
                    if (name != null) smartCast.register(name, subjectType.nonNull())
                }
            }
        }
    }

    // ── when 表达式（T-M12）────────────────────────────────────────────────

    /** when 表达式：subject 检查 + 分支智能转型 + 分支体公共类型（镜像 typeOfIfExpr）。 */
    private fun typeOfWhenExpr(
        expr: YxWhenExpr,
        expected: SemaType?,
    ): SemaType {
        val subject = expr.subject
        val subjectType = subject?.let { typeOf(it) }
        val branchTypes = mutableListOf<SemaType>()
        for (branch in expr.branches) {
            smartCast.enterBlock()
            checkWhenCondition(branch.condition, subject, subjectType)
            branchTypes += typeOf(branch.body, expected)
            smartCast.exitBlock()
        }
        checkWhenExhaustiveness(expr.span.start, expr.branches.map { it.condition }, subjectType, isExpression = true)
        return commonTypeOf(branchTypes, expected)
    }

    /** 多分支公共类型（镜像 typeOfIfExpr 的合并规则：Nothing/Error 传播 + 期望优先）。
     *  缺陷修复：期望为未求解推断变量时不直接返回它——分支体公共类型才是函数体推断
     *  的答案（否则 `fun f() = when { ... }` 的返回变量自绑定成环 → memberLookup 栈溢出）。 */
    private fun commonTypeOf(
        types: List<SemaType>,
        expected: SemaType?,
    ): SemaType {
        val resolvedExpected = expected?.let { SemaType.resolveVar(it) }
        if (resolvedExpected != null && resolvedExpected !is SemaType.InferenceVar && !resolvedExpected.isError) {
            return resolvedExpected
        }
        val resolved = types.map { SemaType.resolveVar(it) }
        var acc: SemaType? = null
        for (t in resolved) {
            if (acc == null) {
                acc = t
            } else if (acc is SemaType.NothingT) {
                acc = t
            } else if (t is SemaType.NothingT) {
                // 保留 acc
            } else if (acc.isError) {
                acc = t
            } else if (!t.isError && TypeAssignability.isAssignable(acc, t)) {
                acc = t
            }
        }
        return acc ?: SemaType.UnitT
    }

    /**
     * when 穷尽性检查（T-M12）：
     * - 密封 subject（非空）未覆盖全部直接子类且无 else → E0034；
     * - 可空密封 subject 无 else → E0034（null 只能由 else 覆盖）；
     * - 非密封 subject 的 when **表达式**无 else → E0034（when 语句允许直落，S-6.3.2）。
     */
    private fun checkWhenExhaustiveness(
        start: SourcePosition,
        conditions: List<YxWhenCondition>,
        subjectType: SemaType?,
        isExpression: Boolean,
    ) {
        val hasElse = conditions.any { it is YxElseCondition }
        // 无 subject（`when { cond -> }`）：无密封/可空性检查；表达式仍需 else（布尔条件不可证穷尽）
        if (subjectType == null) {
            if (isExpression && !hasElse) {
                diagnostics.error(
                    "when 表达式需要 else 分支（无 subject 时布尔条件不可证穷尽）（S-6.3）",
                    start,
                    ErrorCodes.WHEN_NOT_EXHAUSTIVE,
                )
            }
            return
        }
        val resolved = SemaType.resolveVar(subjectType)
        if (resolved.isError) return
        val sealedSym = (resolved as? SemaType.Declared)?.symbol as? YxClassSymbol
        if (sealedSym != null && sealedSym.isSealed) {
            if (hasElse) return
            if (resolved.nullable) {
                diagnostics.error(
                    "可空密封类型 '${resolved.render()}' 的 when 需要 else 分支处理 null（T-M12）",
                    start,
                    ErrorCodes.WHEN_NOT_EXHAUSTIVE,
                )
                return
            }
            val missing = missingSealedSubtypes(conditions, resolved, sealedSym)
            if (missing.isNotEmpty()) {
                diagnostics.error(
                    "密封类型 '${resolved.render()}' 的 when 未穷尽覆盖：缺少分支 ${missing.joinToString(", ") { it.name }}（T-M12）",
                    start,
                    ErrorCodes.WHEN_NOT_EXHAUSTIVE,
                )
            }
            return
        }
        if (isExpression && !hasElse) {
            diagnostics.error("when 表达式需要 else 分支（非密封 subject 无 else）（T-M12）", start, ErrorCodes.WHEN_NOT_EXHAUSTIVE)
        }
    }

    /** 密封基类未被 `is 子类` 分支覆盖的直接子类；命中基类自身（is 基类）视为穷尽。 */
    private fun missingSealedSubtypes(
        conditions: List<YxWhenCondition>,
        subjectType: SemaType,
        sealedSym: YxClassSymbol,
    ): List<YxClassSymbol> {
        val covered = mutableSetOf<YxClassSymbol>()
        for (c in conditions) {
            val isc = c as? YxIsCondition ?: continue
            val t = SemaType.resolveVar(typeResolver.resolve(isc.type, currentFile!!))
            (t as? SemaType.Declared)?.symbol?.let { if (it is YxClassSymbol) covered += it }
            if (TypeAssignability.sameBase(t, subjectType)) return emptyList()
        }
        return directSubtypesOf(sealedSym).filter { it !in covered }
    }

    /** 密封类的直接子类（superType 直接解析到该符号的 Yux 类，跨文件收集）。 */
    private fun directSubtypesOf(sealedSym: YxClassSymbol): List<YxClassSymbol> =
        symbolTable.files
            .flatMap { it.types.values }
            .filterIsInstance<YxClassSymbol>()
            .filter { (it.superType as? SemaType.Declared)?.symbol === sealedSym }

    private fun subjectAsVariable(subject: YxExpr): String? = (subject as? YxIdentifier)?.name

    private fun checkFor(stmt: YxFor) {
        val iterableType = typeOf(stmt.iterable)
        val elemType = elementType(iterableType)
        if (elemType == null && !iterableType.isError) {
            diagnostics.error(
                "'${iterableType.render()}' 不可迭代（S-6.4.1）",
                stmt.iterable.span.start,
                ErrorCodes.LOOP_ITERABLE_INVALID,
            )
        }
        loopDepth++
        smartCast.enterBlock()
        smartCast.shadow(stmt.varName)
        varStack.addLast(mutableMapOf())
        val varSym = VariableSymbol(stmt.varName, elemType ?: SemaType.ANY, isVal = true, stmt.span)
        varSym.definitelyAssigned = true
        varStack.last()[stmt.varName] = varSym
        checkStatement(stmt.body)
        varStack.removeLast()
        smartCast.exitBlock()
        loopDepth--
    }

    private fun checkWhile(stmt: YxWhile) {
        val condType = typeOf(stmt.condition)
        if (!condType.isError && !TypeAssignability.sameBase(condType, SemaType.BOOLEAN)) {
            diagnostics.error(
                "while 条件必须是 Boolean（S-6.2.1），实际 ${condType.render()}",
                stmt.condition.span.start,
                ErrorCodes.CONDITION_NOT_BOOLEAN,
            )
        }
        loopDepth++
        val assignedBefore = definiteAssignmentSnapshot()
        checkStatement(stmt.body)
        // 循环体可能不执行：体内赋值不保证（S-6.1.2 保守分析）
        restoreDefiniteAssignment(assignedBefore)
        loopDepth--
    }

    private fun restoreDefiniteAssignment(before: Map<String, Boolean>) {
        for (i in varStack.indices.reversed()) {
            for ((name, sym) in varStack[i]) {
                sym.definitelyAssigned = before[name] ?: false
            }
        }
    }

    /** 块体内是否出现 `return` 语句（S-5.5.2 缺少返回检查）。 */
    private fun blockHasReturn(block: YxBlock): Boolean {
        fun scan(stmt: YxStmt): Boolean =
            when (stmt) {
                is YxReturn -> true
                is YxBlock -> stmt.statements.any { scan(it) }
                is YxIf -> scan(stmt.thenBranch) || (stmt.elseBranch?.let { scan(it) } ?: false)
                is YxWhen -> stmt.branches.any { scan(it.body) }
                is YxTry -> scan(stmt.body) || stmt.catches.any { scan(it.body) } || (stmt.finallyBody?.let { scan(it) } ?: false)
                else -> false
            }
        return block.statements.any { scan(it) }
    }

    private fun checkReturn(stmt: YxReturn) {
        val returnType = currentReturnType
        val valueType = stmt.value?.let { typeOf(it, expected = returnType) }
        if (returnType != null) {
            if (valueType != null) {
                if (!valueType.isError) inference.expectReturn(valueType, returnType, stmt.span.start, "return")
            } else {
                // 无值 return：必须返回 Unit（S-6.5.1）
                inference.expectReturn(SemaType.UnitT, returnType, stmt.span.start, "return")
            }
        } else {
            diagnostics.error("return 在函数外", stmt.span.start, ErrorCodes.RETURN_TYPE_MISMATCH)
        }
    }

    private fun checkTry(stmt: YxTry) {
        val before = definiteAssignmentSnapshot()
        checkStatement(stmt.body)
        val afterBody = definiteAssignmentSnapshot()
        val catchStates = mutableListOf<Map<String, Boolean>>()
        for (catch in stmt.catches) {
            smartCast.enterBlock()
            smartCast.shadow(catch.paramName)
            // catch 从 try 入口状态开始（体可能已抛异常，变量未必已赋值）
            restoreDefiniteAssignment(before)
            varStack.addLast(mutableMapOf())
            val type = catch.type?.let { typeResolver.resolve(it, currentFile!!) } ?: SemaType.basic("Throwable", nullable = true)
            val sym = VariableSymbol(catch.paramName, type, isVal = true, catch.span)
            sym.definitelyAssigned = true
            varStack.last()[catch.paramName] = sym
            checkStatement(catch.body)
            catchStates += definiteAssignmentSnapshot()
            varStack.removeLast()
            smartCast.exitBlock()
        }
        // S-6.1.2：try 体赋值仅在「体赋值 && 全部 catch 赋值」时保证（无 catch 时体赋值即保证）
        val allCatches =
            if (catchStates.isEmpty()) {
                emptyMap()
            } else {
                val names = catchStates.flatMap { it.keys }.toSet()
                names.associateWith { n -> catchStates.all { it[n] ?: false } }
            }
        for (i in varStack.indices.reversed()) {
            for ((name, sym) in varStack[i]) {
                val beforeV = before[name] ?: false
                val bodyV = afterBody[name] ?: false
                val catchV = if (catchStates.isEmpty()) true else (allCatches[name] ?: false)
                sym.definitelyAssigned = beforeV || (bodyV && catchV)
            }
        }
        stmt.finallyBody?.let { checkStatement(it) }
    }

    // ── 表达式 ──────────────────────────────────────────────────────────────

    private fun typeOf(
        expr: YxExpr,
        expected: SemaType? = null,
    ): SemaType {
        val t = computeType(expr, expected)
        exprTypes[expr] = t
        return t
    }

    private fun computeType(
        expr: YxExpr,
        expected: SemaType?,
    ): SemaType =
        when (expr) {
            is YxIntLiteral -> {
                literalIntType(expr.text, expected, expr.span.start)
            }

            is YxFloatLiteral -> {
                literalFloatType(expr.text, expected)
            }

            is YxCharLiteral -> {
                SemaType.CHAR
            }

            is YxBoolLiteral -> {
                SemaType.BOOLEAN
            }

            is YxNullLiteral -> {
                val expectedResolved = expected?.let { SemaType.resolveVar(it) }
                when {
                    // 无期望类型：无法推断（S-4.5.4）
                    expected == null -> {
                        diagnostics.error("null 字面量无法推断类型（S-4.5.4）", expr.span.start, ErrorCodes.INFERENCE_FAILURE)
                        SemaType.ErrorT
                    }

                    // 期望为未求解推断变量：null 无法提供类型信息
                    expectedResolved is SemaType.InferenceVar -> {
                        diagnostics.error("null 字面量无法推断类型（S-4.5.4）", expr.span.start, ErrorCodes.INFERENCE_FAILURE)
                        SemaType.ErrorT
                    }

                    // Nothing 不可接收 null（S-4.1.1）
                    expectedResolved is SemaType.NothingT -> {
                        diagnostics.error("Nothing 不可赋值 null", expr.span.start, ErrorCodes.TYPE_MISMATCH)
                        SemaType.ErrorT
                    }

                    else -> {
                        expected.asNullable()
                    }
                }
            }

            is YxStringLiteral -> {
                SemaType.STRING
            }

            is YxRawStringLiteral -> {
                SemaType.STRING
            }

            is YxStringText -> {
                SemaType.STRING
            }

            is YxStringTemplate -> {
                expr.parts.forEach { typeOf(it, expected = SemaType.STRING) }
                SemaType.STRING
            }

            is YxIdentifier -> {
                typeOfIdentifier(expr)
            }

            is YxMemberAccess -> {
                typeOfMemberAccess(expr)
            }

            is YxCall -> {
                typeOfCall(expr)
            }

            is YxTypeCall -> {
                typeOfTypeCall(expr)
            }

            is YxIfExpr -> {
                typeOfIfExpr(expr, expected)
            }

            is YxWhenExpr -> {
                typeOfWhenExpr(expr, expected)
            }

            is YxIndexExpr -> {
                typeOfIndexExpr(expr)
            }

            is YxTypeReference -> {
                val resolved = typeResolver.resolve(expr.type, currentFile!!)
                if (resolved.isError) SemaType.ErrorT else resolved
            }

            is YxBinary -> {
                typeOfBinary(expr, expected)
            }

            is YxUnary -> {
                typeOfUnary(expr, expected)
            }

            is YxLambda -> {
                typeOfLambda(expr, expected)
            }

            is YxBlockLambda -> {
                typeOfBlockLambda(expr, expected)
            }

            is YxParen -> {
                typeOf(expr.expr, expected)
            }

            is YxThrow -> {
                typeOf(expr.expr)
                SemaType.NothingT
            }

            is YxAwait -> {
                typeOfAwait(expr)
            }

            is YxIs -> {
                typeOf(expr.expr)
                SemaType.BOOLEAN
            }

            is YxAs -> {
                typeOf(expr.expr)
                typeResolver.resolve(expr.type, currentFile!!)
            }

            is YxNullable -> {
                typeOf(expr.expr).asNullable()
            }

            is YxRange -> {
                val from = typeOf(expr.from)
                val to = typeOf(expr.to)
                if (!SemaType.isNumeric(from) || !SemaType.isNumeric(to)) {
                    diagnostics.error("区间两端需要数字操作数（S-6.4.2）", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                SemaType.RANGE
            }

            is YxAssign -> {
                checkAssign(expr)
                typeOf(expr.value)
            }

            is YxThis -> {
                // 扩展函数体内 `this` 为 receiver（M9），否则必须处于类上下文
                if (currentReceiver != null) {
                    currentReceiver!!
                } else if (currentClass == null) {
                    diagnostics.error("this 在类外使用", expr.span.start, ErrorCodes.THIS_OUTSIDE_CLASS)
                    SemaType.ErrorT
                } else {
                    SemaType.Declared(currentClass!!, emptyList(), nullable = false)
                }
            }

            is YxSuper -> {
                val superType = currentClass?.superType
                if (currentClass == null) {
                    diagnostics.error("super 在类外使用", expr.span.start, ErrorCodes.SUPER_OUTSIDE_CLASS)
                    SemaType.ErrorT
                } else {
                    superType ?: SemaType.ANY
                }
            }
        }

    private fun typeOfIdentifier(expr: YxIdentifier): SemaType {
        val sym = lookupVariable(expr.name)
        if (sym != null) {
            resolvedRefs[expr] = sym
            if (!sym.definitelyAssigned) {
                diagnostics.error(
                    "变量 '${expr.name}' 使用前未确定性赋值（S-6.1.2）",
                    expr.span.start,
                    ErrorCodes.VARIABLE_NOT_INITIALIZED,
                )
            }
            // 智能转型仅在符号未重赋值时生效（02-§7.3）
            val casted = if (sym.reassigned) null else smartCast.castedType(expr.name)
            return casted ?: sym.type ?: SemaType.ErrorT
        }
        // 类成员属性（隐式 this，S-5.2；沿父类链，S-8.7.1）
        currentClass?.propertyIncludingSuper(expr.name)?.let { prop ->
            // 访问控制（S-5.1.4，缺陷 10）：隐式 this 访问须在声明类/子类内
            prop.owner?.let { checkMemberAccess(it, prop.visibility, expr.span, "成员 '${expr.name}'") }
            resolvedRefs[expr] = prop
            return prop.type ?: SemaType.ErrorT
        }
        // 顶层属性（M9）：文件级静态字段（S-5.6），如 `cooldowns`
        currentFile?.topLevelProperties?.get(expr.name)?.let { prop ->
            resolvedRefs[expr] = prop
            return prop.type ?: SemaType.ErrorT
        }
        // 函数引用（S-4.4.2）
        val fns = resolveFunctions(expr.name)
        if (fns.isNotEmpty()) {
            resolvedRefs[expr] = fns.first()
            return functionType(fns.first())
        }
        // 类名作为值（极少）；否则未解析
        currentFile?.types?.get(expr.name)?.let { return SemaType.Declared(it, emptyList(), nullable = false) }
        diagnostics.error("未解析的标识符 '${expr.name}'", expr.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
        return SemaType.ErrorT
    }

    private fun typeOfMemberAccess(expr: YxMemberAccess): SemaType {
        val receiverType = typeOf(expr.receiver)
        if (receiverType.isError) return SemaType.ErrorT
        if (expr.receiver is YxTypeReference) {
            return staticMemberType(receiverType, expr.name, expr)
        }
        val result = memberLookup(receiverType, expr.name)
        if (result == null) {
            diagnostics.error(
                "成员 '${expr.name}' 不存在于类型 '${receiverType.render()}'",
                expr.span.start,
                ErrorCodes.UNRESOLVED_MEMBER,
            )
            return SemaType.ErrorT
        }
        val (type, isPropertyRead, sym) = result
        // 访问控制（S-5.1.4，缺陷 10）
        sym?.let { memberAccessInfo(it)?.let { (owner, vis) -> checkMemberAccess(owner, vis, expr.span, "成员 '${expr.name}'") } }
        if (isPropertyRead) nullGuard.checkMemberRead(expr, receiverType, isPropertyRead = true)
        return type
    }

    private fun staticMemberType(
        receiverType: SemaType,
        name: String,
        node: YxMemberAccess,
    ): SemaType {
        val sym = (receiverType as? SemaType.Declared)?.symbol ?: (receiverType as? SemaType.Basic)?.let { jvmForBasic(it.name) }
        if (sym is JvmClassSymbol) {
            sym.fields.firstOrNull { it.name == name && it.isStatic }?.let { return it.type }
            sym.propertyType(name)?.let { return it }
            sym.staticMethods.firstOrNull { it.name == name }?.let { return functionType(it) }
        }
        diagnostics.error("静态成员 '$name' 不存在", node.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    /** 成员查找：返回 (类型, 是否属性读取, 引用符号)。沿父类链解析（S-8.7.1，缺陷 9）。 */
    private fun memberLookup(
        receiver: SemaType,
        name: String,
    ): Triple<SemaType, Boolean, Symbol?>? =
        when (receiver) {
            is SemaType.Declared -> {
                val sym = receiver.symbol
                when (sym) {
                    is YxClassSymbol -> {
                        sym.propertyIncludingSuper(name)?.let { return Triple(it.type ?: SemaType.ErrorT, true, it) }
                        sym.functionsIncludingSuper(name).firstOrNull()?.let { return Triple(functionType(it), false, it) }
                        // 继承 Object 成员（S-8.7.1）：data 类生成 toString/equals/hashCode（S-5.3.1），
                        // 普通类继承自 Object——成员查找统一回退到 java.lang.Object（M5-11 data 验收依赖）
                        objectMember(name)?.let { return Triple(functionType(it), false, null) }
                        null
                    }

                    is JvmClassSymbol -> {
                        sym.propertyType(name)?.let { return Triple(it, true, null) }
                        sym.methodNamed(name)?.let { return Triple(functionType(it), false, null) }
                        null
                    }

                    else -> {
                        null
                    }
                }
            }

            is SemaType.Basic -> {
                if (receiver.name == "String" && name == "length") return Triple(SemaType.INT, true, null)
                jvmForBasic(receiver.name)?.let { jc ->
                    jc.propertyType(name)?.let { return Triple(it, true, null) }
                    // 方法调用（S-8.5）：`s.replace(a, b)` → String#replace（M9，memberLookup 补方法查找）
                    jc.methodNamed(name)?.let { return Triple(functionType(it), false, null) }
                }
                null
            }

            is SemaType.TypeParam -> {
                null
            }

            is SemaType.Function -> {
                null
            }

            is SemaType.InferenceVar -> {
                // 环防护（缺陷修复）：自绑定/环状解链时 resolveVar 返回 ErrorT，不再无限递归
                val resolved = SemaType.resolveVar(receiver)
                if (resolved !is SemaType.InferenceVar) {
                    memberLookup(resolved, name)
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    /** java.lang.Object 成员回退（用户类继承 Object，S-8.7.1）。 */
    private fun objectMember(name: String): JvmMethodSymbol? = classPath.resolve("java.lang.Object")?.methodNamed(name)

    /** 成员访问控制检查（S-5.1.4，缺陷 10）：private 仅限声明类内；protected 仅限子类内。 */
    private fun checkMemberAccess(
        owner: YxClassSymbol,
        visibility: YxVisibility,
        span: SourceSpan?,
        what: String,
    ) {
        if (visibility == YxVisibility.PUBLIC) return
        val current = currentClass
        if (current == null) {
            diagnostics.error("$what 不可访问（${visibility.name.lowercase()}）", span?.start, ErrorCodes.ILLEGAL_ACCESS)
            return
        }
        if (visibility == YxVisibility.PRIVATE) {
            if (current !== owner) {
                diagnostics.error("$what 不可访问（private）", span?.start, ErrorCodes.ILLEGAL_ACCESS)
            }
            return
        }
        var cls: YxClassSymbol? = current
        while (cls != null) {
            if (cls === owner) return
            cls = superClassOf(cls)
        }
        diagnostics.error("$what 不可访问（protected）", span?.start, ErrorCodes.ILLEGAL_ACCESS)
    }

    /** 符号的 (声明类, 可见性)；非类成员返回 null。 */
    private fun memberAccessInfo(sym: Symbol): Pair<YxClassSymbol, YxVisibility>? =
        when (sym) {
            is PropertySymbol -> sym.owner?.let { it to sym.visibility }
            is FunctionSymbol -> sym.owner?.let { it to sym.visibility }
            else -> null
        }

    private fun jvmForBasic(name: String): JvmClassSymbol? =
        when (name) {
            "String" -> classPath.resolve("java.lang.String")

            "Int" -> classPath.resolve("java.lang.Integer")

            "Long" -> classPath.resolve("java.lang.Long")

            "Float" -> classPath.resolve("java.lang.Float")

            "Double" -> classPath.resolve("java.lang.Double")

            "Boolean" -> classPath.resolve("java.lang.Boolean")

            "Char" -> classPath.resolve("java.lang.Character")

            "Byte" -> classPath.resolve("java.lang.Byte")

            "Any" -> classPath.resolve("java.lang.Object")

            "Iterable" -> classPath.resolve("java.lang.Iterable")

            // 与 SymbolTable.Builtins / SemaType.RANGE 对齐（缺陷修复）：Range 成员映射到
            // yux.core.Range（此前误映射 java.lang.Integer，使区间值错误解析 Integer 的方法；
            // for 迭代不依赖此映射——StmtGen 经 iteratorOwner 直接定位 yux.core.Range）
            "Range" -> classPath.resolve("yux.core.Range")

            else -> null
        }

    // ── 调用 ────────────────────────────────────────────────────────────────

    private fun typeOfCall(call: YxCall): SemaType =
        when (val callee = call.callee) {
            is YxIdentifier -> {
                val fns = resolveFunctions(callee.name)
                if (fns.isEmpty()) {
                    val varSym = lookupVariable(callee.name)
                    if (varSym != null) {
                        val t = varSym.type ?: SemaType.ErrorT
                        if (t is SemaType.Function) {
                            // B3：函数类型局部/参数调用——登记解析符号，供 IRGen 捕获分析
                            // （自由变量识别）与 FnInvoke 生成使用（与 typeOfIdentifier 一致）
                            resolvedRefs[callee] = varSym
                            checkFunctionCallArgs(t.params, t.ret, call)
                        } else {
                            diagnostics.error("'${callee.name}' 不是可调用函数", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                            SemaType.ErrorT
                        }
                    } else {
                        diagnostics.error("未解析的调用 '${callee.name}'", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                        SemaType.ErrorT
                    }
                } else {
                    resolvedRefs[callee] = fns.first()
                    val ret = checkCallable(fns, call, "函数 '${callee.name}'")
                    // 双 ABI（T-M14）：async fun 在同步上下文调用返回 Task；async 上下文为挂起调用返回声明类型
                    if (fns.first().isAsync && !isInAsyncContext()) taskType else ret
                }
            }

            is YxMemberAccess -> {
                val receiverType = typeOf(callee.receiver)
                if (receiverType.isError) return SemaType.ErrorT
                val result =
                    if (callee.receiver is YxTypeReference) {
                        checkStaticCall(receiverType, callee.name, call)
                    } else {
                        val ret = checkInstanceCall(receiverType, callee.name, call)
                        // 取值调用守卫（02-§7.4）：非 Unit 返回 + 可空接收者
                        nullGuard.checkMethodCall(callee, receiverType, ret)
                        ret
                    }
                result
            }

            is YxTypeReference -> {
                // `Type(args)` 形式已被 YxTypeCall 处理；此处兜底（静态调用）
                val t = typeOf(callee)
                if (t is SemaType.Declared) {
                    checkStaticCall(t, t.symbol.name, call)
                } else {
                    diagnostics.error("类型不可调用", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                    SemaType.ErrorT
                }
            }

            is YxParen -> {
                applyFunctionType(typeOf(callee), call)
            }

            is YxLambda -> {
                diagnostics.error("Lambda 不可直接调用", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                SemaType.ErrorT
            }

            else -> {
                applyFunctionType(typeOf(callee), call)
            }
        }

    private fun applyFunctionType(
        type: SemaType,
        call: YxCall,
    ): SemaType {
        val fn = type as? SemaType.Function
        if (fn == null) {
            diagnostics.error("'${type.render()}' 不可调用", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
            return SemaType.ErrorT
        }
        return checkFunctionCallArgs(fn.params, fn.ret, call)
    }

    /**
     * `await <expr>`（S-8.4.2，T-M14）：
     * - 仅 async 上下文合法（普通 lambda 体已清零 async 上下文 → 此处报错）；
     * - 操作数为 async fun 调用（async 上下文内已是挂起调用，类型=声明返回类型）；
     * - 操作数为 Task / CompletableFuture → 返回 `Any?`（Task 非泛型，S-8.4）。
     */
    private fun typeOfAwait(expr: YxAwait): SemaType {
        if (!isInAsyncContext()) {
            diagnostics.error(
                "`await` 只能在 async fun / async { } 内使用（T-M14）",
                expr.span.start,
                ErrorCodes.UNRESOLVED_REFERENCE,
            )
        }
        val operandType = typeOf(expr.operand)
        if (isAsyncFunCall(expr.operand)) return operandType
        val base = SemaType.resolveVar(operandType)
        val awaitable =
            base is SemaType.Declared && base.symbol is JvmClassSymbol &&
                (
                    base.symbol.qualifiedName == "yux.async.Task" ||
                        base.symbol.qualifiedName == "java.util.concurrent.CompletableFuture"
                )
        if (!operandType.isError && !awaitable) {
            diagnostics.error(
                "`await` 操作数必须是 Task / CompletableFuture 或 async fun 调用（T-M14），实际 '${operandType.render()}'",
                expr.span.start,
                ErrorCodes.TYPE_MISMATCH,
            )
        }
        return if (awaitable) SemaType.basic("Any", nullable = true) else operandType
    }

    /** 操作数是否为 async fun 调用（双 ABI 挂起调用）。 */
    private fun isAsyncFunCall(expr: YxExpr): Boolean =
        when (expr) {
            is YxCall -> {
                val sym = resolvedRefs[expr.callee]
                sym is FunctionSymbol && sym.isAsync
            }

            else -> {
                false
            }
        }

    private fun checkFunctionCallArgs(
        params: List<SemaType>,
        ret: SemaType,
        call: YxCall,
    ): SemaType {
        if (call.args.size !=
            params.size
        ) {
            diagnostics.error(
                "实参数目不匹配: 期望 ${params.size} 个，实际 ${call.args.size} 个",
                call.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
        }
        val max = minOf(call.args.size, params.size)
        for (i in 0 until max) {
            val argType = typeOf(call.args[i], expected = params[i])
            if (!argType.isError) inference.expectAssignable(argType, params[i], call.args[i].span.start, "实参 ${i + 1}")
        }
        return ret
    }

    private fun checkInstanceCall(
        receiverType: SemaType,
        name: String,
        call: YxCall,
    ): SemaType {
        val receiver = receiverType
        if (receiver is SemaType.Declared && receiver.symbol is YxClassSymbol) {
            val sym = receiver.symbol
            // 沿父类链解析（S-8.7.1，缺陷 9）：子类实例可调用父类方法/属性
            val fns = sym.functionsIncludingSuper(name)
            if (fns.isEmpty()) {
                val prop = sym.propertyIncludingSuper(name)
                if (prop != null) {
                    // 访问控制（S-5.1.4，缺陷 10）
                    prop.owner?.let { checkMemberAccess(it, prop.visibility, call.span, "成员 '$name'") }
                    return applyFunctionType(prop.type ?: SemaType.ErrorT, call)
                }
                // Object 方法回退（S-8.7.1，与 memberLookup 一致）
                objectMember(name)?.let { return checkJvmCall(listOf(it), call) }
                lookupExtensionCall(receiverType, name, call)?.let { return it }
                diagnostics.error("方法 '$name' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
                return SemaType.ErrorT
            }
            // 访问控制（S-5.1.4，缺陷 10）：取最派生候选的声明类
            fns.first().owner?.let { checkMemberAccess(it, fns.first().visibility, call.span, "方法 '$name'") }
            val ret = checkCallable(fns, call, "方法 '$name'")
            // 双 ABI（T-M14）：async 成员在同步上下文调用返回 Task
            return if (fns.first().isAsync && !isInAsyncContext()) taskType else ret
        }
        if (receiver is SemaType.Declared && receiver.symbol is JvmClassSymbol) {
            val sym = receiver.symbol
            // T-M11-3：注册表成员调用优先降级为 stdlib 静态调用——注册表定义语言级成员表面，
            // JDK 同名实例方法（如 ArrayList.forEach(Consumer)/sort(Comparator)）不参与解析
            if (JvmExtensions.find(sym.qualifiedName, name) != null) {
                checkJvmExtension(receiverType, name, call)?.let { return it }
            }
            val methods = sym.methods.filter { it.name == name }
            if (methods.isEmpty()) {
                sym.propertyType(name)?.let { return applyFunctionType(it, call) }
                lookupExtensionCall(receiverType, name, call)?.let { return it }
                checkJvmExtension(receiverType, name, call)?.let { return it }
                diagnostics.error("方法 '$name' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
                return SemaType.ErrorT
            }
            return checkJvmCall(methods, call)
        }
        if (receiver is SemaType.Basic) {
            val jc = jvmForBasic(receiver.name)
            if (jc != null) {
                // T-M11-3：注册表成员优先（如 String.split → Text.split 的字面语义与 List 返回值）
                if (JvmExtensions.find(jc.qualifiedName, name) != null) {
                    checkJvmExtension(receiverType, name, call)?.let { return it }
                }
                val methods = jc.methods.filter { it.name == name }
                if (methods.isNotEmpty()) return checkJvmCall(methods, call)
                // 基础类型用户扩展函数（`fun String.shout()`）：注册表/JVM 成员未命中后查找
                lookupExtensionCall(receiverType, name, call)?.let { return it }
                checkJvmExtension(receiverType, name, call)?.let { return it }
            }
        }
        if (receiver is SemaType.Function) {
            return applyFunctionType(receiver, call)
        }
        diagnostics.error("方法 '$name' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    private fun checkStaticCall(
        receiverType: SemaType,
        name: String,
        call: YxCall,
    ): SemaType {
        val sym = (receiverType as? SemaType.Declared)?.symbol ?: (receiverType as? SemaType.Basic)?.let { jvmForBasic(it.name) }
        if (sym is JvmClassSymbol) {
            val methods = sym.staticMethods.filter { it.name == name }
            if (methods.isNotEmpty()) return checkJvmCall(methods, call)
            sym.fields.firstOrNull { it.name == name && it.isStatic }?.let { return applyFunctionType(it.type, call) }
            // JavaBean getter 属性调用（M9）：`Bukkit.onlinePlayers()` → `getOnlinePlayers()`（Paper API）
            val cap = name.replaceFirstChar { it.uppercaseChar() }
            val getter =
                sym.staticMethods.firstOrNull { it.name == "get$cap" && it.params.isEmpty() }
                    ?: sym.staticMethods.firstOrNull { it.name == "is$cap" && it.params.isEmpty() }
            if (getter != null) return checkJvmCall(listOf(getter), call)
        }
        diagnostics.error("静态方法 '$name' 不存在", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    private fun checkJvmCall(
        methods: List<JvmMethodSymbol>,
        call: YxCall,
    ): SemaType {
        val byArity = methods.filter { it.params.size == call.args.size }
        if (byArity.isEmpty()) {
            // 无匹配元数的重载：明确诊断并列出签名，不静默选第一个候选
            diagnostics.error(
                "没有匹配的重载: 实参数目 ${call.args.size} 与候选签名不符 [${jvmSignatures(methods)}]",
                call.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
            call.args.forEach { typeOf(it) }
            return SemaType.ErrorT
        }
        // 重载选择：先用无期望实参类型精确匹配（Math.max(int,int) 不得落到 long/double 重载；
        // expected 收窄会污染字面量类型，M9），失败后再按期望类型匹配（playSound float 参数）
        val argTypes = call.args.map { typeOf(it) }
        val exact =
            byArity.firstOrNull { m ->
                m.params.indices.all { i -> TypeAssignability.sameBase(argTypes[i], m.params[i]) }
            }
        val matched =
            exact ?: byArity.firstOrNull { m ->
                m.params.indices.all { i -> TypeAssignability.isAssignable(argTypes[i], m.params[i]) }
            } ?: byArity.firstOrNull { m ->
                m.params.indices.all { i -> TypeAssignability.isAssignable(typeOf(call.args[i], expected = m.params[i]), m.params[i]) }
            }
        if (matched == null) {
            // 元数匹配但类型不匹配：明确诊断并列出签名，不静默选第一个候选
            diagnostics.error(
                "没有匹配的重载: 实参类型与候选签名不符 [${jvmSignatures(byArity)}]",
                call.span.start,
                ErrorCodes.TYPE_MISMATCH,
            )
            return SemaType.ErrorT
        }
        return checkFunctionCallArgs(matched.params, matched.returnType, call)
    }

    /** JVM 候选签名列表（诊断用）。 */
    private fun jvmSignatures(methods: List<JvmMethodSymbol>): String =
        methods.joinToString("; ") { m ->
            "(${m.params.joinToString(", ") { it.render() }}) -> ${m.returnType.render()}"
        }

    private fun checkCallable(
        candidates: List<FunctionSymbol>,
        call: YxCall,
        kind: String,
    ): SemaType {
        val byArity = candidates.filter { canCallWith(it, call.args.size) }
        val fn = byArity.firstOrNull() ?: candidates.firstOrNull()
        if (fn == null) {
            diagnostics.error("$kind 无匹配定义", call.span.start, ErrorCodes.ARGUMENT_COUNT_MISMATCH)
            return SemaType.ErrorT
        }
        if (byArity.isEmpty()) {
            val need = candidates.minOf { it.params.count { p -> !p.hasDefault } }
            diagnostics.error(
                "$kind 实参数目不匹配: 至少需要 $need 个，实际 ${call.args.size} 个",
                call.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
            call.args.forEach { typeOf(it) }
            return fn.returnType ?: SemaType.ErrorT
        }
        val max = minOf(call.args.size, fn.params.size)
        for (i in 0 until max) {
            val param = fn.params[i]
            val argType = typeOf(call.args[i], expected = param.type)
            if (!argType.isError) inference.expectAssignable(argType, param.type, call.args[i].span.start, "实参 ${i + 1}")
        }
        return fn.returnType ?: SemaType.ErrorT
    }

    private fun canCallWith(
        fn: FunctionSymbol,
        argCount: Int,
    ): Boolean {
        val required = fn.params.count { !it.hasDefault }
        return argCount >= required && argCount <= fn.params.size
    }

    // ── 构造 ────────────────────────────────────────────────────────────────

    private fun typeOfTypeCall(call: YxTypeCall): SemaType {
        val resolved = typeResolver.resolve(call.type, currentFile!!)
        if (resolved.isError) return SemaType.ErrorT
        if (resolved !is SemaType.Declared) {
            diagnostics.error("类型 '${resolved.render()}' 不可构造", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
            return SemaType.ErrorT
        }
        // 集合接口构造降级（S-4.3.2 扩展 / M9）：`Map X Y()` / `List X()` / `Set X()`
        // 映射到 JDK 具体实现类（Map→HashMap、List→ArrayList、Set→HashSet），
        // 使空集合构造（04-§5/§6 的 `Map String LocationData()`）可直接使用。
        val concrete = concreteCollectionType(resolved) ?: resolved
        val sym = concrete.symbol
        // T-M12：密封类不可直接实例化（仅可通过其 sealed 子类构造具体实例）
        if (sym is YxClassSymbol && sym.isSealed) {
            diagnostics.error(
                "密封类 '${sym.name}' 不可直接实例化（T-M12）：请构造其 sealed 子类",
                call.span.start,
                ErrorCodes.SEALED_INSTANTIATION,
            )
        }
        val constructorParams: List<SemaType> =
            when (sym) {
                is JvmClassSymbol -> {
                    val ctor = sym.constructors.firstOrNull { it.params.size == call.args.size } ?: sym.constructors.firstOrNull()
                    ctor?.params ?: emptyList()
                }

                is YxClassSymbol -> {
                    sym.properties().map { it.type ?: SemaType.ErrorT }
                }

                else -> {
                    emptyList()
                }
            }
        if (call.args.size != constructorParams.size) {
            diagnostics.error(
                "构造实参数目不匹配: '${sym.name}' 需要 ${constructorParams.size} 个，实际 ${call.args.size} 个",
                call.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
        }
        val max = minOf(call.args.size, constructorParams.size)
        for (i in 0 until max) {
            val argType = typeOf(call.args[i], expected = constructorParams[i])
            if (!argType.isError) inference.expectAssignable(argType, constructorParams[i], call.args[i].span.start, "构造实参 ${i + 1}")
        }
        return concrete
    }

    /** 集合接口 → 具体实现类型：`java.util.Map`→`HashMap`、`List`→`ArrayList`、`Set`→`HashSet`（M9）。 */
    private fun concreteCollectionType(resolved: SemaType.Declared): SemaType.Declared? {
        val sym = resolved.symbol as? JvmClassSymbol ?: return null
        val impl =
            when (sym.qualifiedName) {
                "java.util.Map", "java.util.SortedMap" -> "java.util.HashMap"
                "java.util.List" -> "java.util.ArrayList"
                "java.util.Set", "java.util.SortedSet" -> "java.util.HashSet"
                else -> return null
            }
        val implSym = classPath.resolve(impl) ?: return null
        return SemaType.Declared(implSym, resolved.args, resolved.nullable)
    }

    /** 扩展函数调用（M9）：`receiver.name(args)` → 匹配 `fun Receiver.name(receiver, args)`。 */
    private fun lookupExtensionCall(
        receiverType: SemaType,
        name: String,
        call: YxCall,
    ): SemaType? {
        val candidates = mutableListOf<FunctionSymbol>()
        // 跨文件扩展函数（M9）：同包顶层扩展（S-5.5.3），如 HomeCommand 用 HomeData.yux 的 toData/toLocation
        for (file in symbolTable.files) {
            file.extensionFunctions.values
                .flatten()
                .filter { it.name == name && it.receiverType != null }
                .let { candidates += it }
        }
        val recvKey = receiverType.nonNull().render()
        val byReceiver = candidates.filter { it.receiverType?.nonNull()?.render() == recvKey }
        val byArity = byReceiver.filter { it.params.size == call.args.size + 1 }
        val pool = byArity.ifEmpty { byReceiver }.ifEmpty { candidates }
        val fn = pool.firstOrNull { it.params.size == call.args.size + 1 } ?: pool.firstOrNull() ?: return null
        resolvedRefs[call.callee] = fn
        val fnParams = fn.params
        if (fnParams.isEmpty()) return fn.returnType ?: SemaType.UnitT
        val recvParam = fnParams[0]
        // 可空接收者（空守卫路径）：`p.location.toData()` 中 location 可空——守卫插入后允许
        val recvTarget = recvParam.type ?: SemaType.ANY
        if (!(receiverType.nullable && !recvTarget.nullable)) {
            inference.expectAssignable(receiverType, recvTarget, call.callee.span.start, "扩展函数接收者")
        } else if (receiverType !is SemaType.InferenceVar && receiverType.nonNull().render() != recvTarget.nonNull().render()) {
            inference.expectAssignable(receiverType, recvTarget, call.callee.span.start, "扩展函数接收者")
        }
        val rest = fnParams.drop(1)
        val max = minOf(rest.size, call.args.size)
        for (i in 0 until max) {
            val argType = typeOf(call.args[i], expected = rest[i].type)
            if (!argType.isError) {
                inference.expectAssignable(
                    argType,
                    rest[i].type ?: SemaType.ANY,
                    call.args[i].span.start,
                    "扩展函数实参 ${i + 1}",
                )
            }
        }
        return fn.returnType ?: SemaType.UnitT
    }

    /**
     * JVM 扩展函数调用（T-M11-3）：`xs.map {}` / `s.uppercase()` 经 [JvmExtensions]
     * 注册表降级为 yux-stdlib 静态方法调用（receiver 前置为实参 0）。
     * 未命中注册表 / 宿主类 / 静态方法时返回 null（维持 UNRESOLVED_MEMBER 路径）。
     */
    private fun checkJvmExtension(
        receiverType: SemaType,
        name: String,
        call: YxCall,
    ): SemaType? {
        val jc = jvmClassOf(receiverType) ?: return null
        val entry = JvmExtensions.find(jc.qualifiedName, name) ?: return null
        val host = classPath.resolve(entry.first) as? JvmClassSymbol ?: return null
        val method = host.staticMethods.firstOrNull { it.name == entry.second } ?: return null
        // 实参数校验：静态方法参数 = receiver(param 0) + 实参
        if (call.args.size != method.params.size - 1) {
            diagnostics.error(
                "扩展函数 '$name' 实参数目不匹配: 需要 ${method.params.size - 1} 个，实际 ${call.args.size} 个",
                call.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
            return method.returnType
        }
        val elem = elementType(receiverType)
        call.args.forEachIndexed { i, arg ->
            val param = method.params[i + 1]
            // FunctionN 形参：用接收者元素类型替换函数参数类型（`map { it * 10 }` 的 it 为元素类型）
            val expected =
                (param as? SemaType.Declared)?.let { functionTypeOfJvm(it) }?.let { fn ->
                    if (elem != null && fn.params.isNotEmpty()) {
                        SemaType.Function(List(fn.params.size) { elem }, fn.ret, nullable = false)
                    } else {
                        fn
                    }
                } ?: param
            val argType = typeOf(arg, expected = expected)
            if (!argType.isError) inference.expectAssignable(argType, expected, arg.span.start, "实参 ${i + 1}")
        }
        return method.returnType
    }

    /** 语义类型 → JVM 类符号（镜像 JvmCallResolver.jvmClassOf）。 */
    private fun jvmClassOf(receiverType: SemaType): JvmClassSymbol? =
        when (val t = SemaType.resolveVar(receiverType)) {
            is SemaType.Declared -> t.symbol as? JvmClassSymbol
            is SemaType.Basic -> jvmForBasic(t.name)
            else -> null
        }

    private fun typeOfIndexExpr(expr: YxIndexExpr): SemaType {
        val base = SemaType.resolveVar(typeOf(expr.base))
        val idx = typeOf(expr.index)
        val result: SemaType =
            when (base) {
                is SemaType.Declared -> {
                    val sym = base.symbol
                    if (sym is JvmClassSymbol) {
                        when {
                            // JVM 数组：元素类型本身带可空性（原语数组非空，引用数组可空），不再统一加 ?（S-6.4.1）
                            sym.qualifiedName.startsWith("[") -> elementType(base) ?: SemaType.ANY

                            sym.qualifiedName == "java.util.Map" || sym.qualifiedName == "java.util.HashMap" ||
                                sym.qualifiedName == "java.util.LinkedHashMap" -> (base.args.getOrNull(1) ?: SemaType.ANY).asNullable()

                            elementType(base) != null -> (elementType(base)!!).asNullable()

                            else -> SemaType.ANY
                        }
                    } else {
                        SemaType.ANY
                    }
                }

                is SemaType.Basic -> {
                    when (base.name) {
                        "String" -> {
                            if (!idx.isError) {
                                diagnostics.error(
                                    "String 索引访问不支持（v0.1，用 get 方法）",
                                    expr.index.span.start,
                                    ErrorCodes.UNRESOLVED_MEMBER,
                                )
                            }
                            SemaType.CHAR
                        }

                        else -> {
                            SemaType.ANY
                        }
                    }
                }

                else -> {
                    SemaType.ANY
                }
            }
        return result
    }

    /** if 表达式（M9）：条件须 Boolean，结果类型为两分支的公共类型（期望优先）。 */
    private fun typeOfIfExpr(
        expr: YxIfExpr,
        expected: SemaType?,
    ): SemaType {
        val condType = typeOf(expr.condition)
        if (!condType.isError && !TypeAssignability.sameBase(condType, SemaType.BOOLEAN)) {
            diagnostics.error(
                "if 条件必须是 Boolean（S-6.2.1），实际 ${condType.render()}",
                expr.condition.span.start,
                ErrorCodes.CONDITION_NOT_BOOLEAN,
            )
        }
        val thenType = typeOf(expr.thenExpr, expected)
        val elseType = typeOf(expr.elseExpr, expected)
        val thenResolved = SemaType.resolveVar(thenType)
        val elseResolved = SemaType.resolveVar(elseType)
        if (thenResolved is SemaType.NothingT) return elseResolved
        if (elseResolved is SemaType.NothingT) return thenResolved
        // 期望为具体类型时以期望优先；未求解推断变量不返回（否则表达式体推断自绑定成环）
        val resolvedExpected = expected?.let { SemaType.resolveVar(it) }
        if (resolvedExpected != null && resolvedExpected !is SemaType.InferenceVar && !resolvedExpected.isError) {
            return resolvedExpected
        }
        if (thenResolved.isError) return elseResolved
        if (elseResolved.isError) return thenResolved
        return if (TypeAssignability.isAssignable(thenResolved, elseResolved)) {
            elseResolved
        } else {
            thenResolved
        }
    }

    // ── 运算符 / Lambda ─────────────────────────────────────────────────────

    private fun typeOfBinary(
        expr: YxBinary,
        expected: SemaType?,
    ): SemaType { // `x == null` / `null == x`：null 字面量以对侧类型为期望（S-8.1）
        if (expr.op == "==" || expr.op == "!=") {
            if (expr.right is YxNullLiteral) {
                val left = typeOf(expr.left)
                val right = typeOf(expr.right, expected = left)
                return equalityResult(expr.span, left, right)
            }
            if (expr.left is YxNullLiteral) {
                val right = typeOf(expr.right)
                val left = typeOf(expr.left, expected = right)
                return equalityResult(expr.span, left, right)
            }
        }
        val left = typeOf(expr.left)
        val right = typeOf(expr.right)
        return binaryOpResult(expr.op, left, right, expr.span)
    }

    /** 二元运算结果类型与操作数校验（S-7.5/S-7.6）：复合赋值 `x op= e` 展开复用同一套规则。 */
    private fun binaryOpResult(
        op: String,
        left: SemaType,
        right: SemaType,
        span: SourceSpan,
    ): SemaType =
        when (op) {
            "+" -> {
                if (left is SemaType.Basic && left.name == "String" || right is SemaType.Basic && right.name == "String") {
                    // 字符串拼接：另一侧须为 String 或数字（S-7.6.1），防 `print "a" + "b"` 落到 Unit
                    val otherOk =
                        left.isError || right.isError ||
                            (left is SemaType.Basic && left.name == "String") ||
                            (right is SemaType.Basic && right.name == "String") ||
                            SemaType.isNumeric(left) && SemaType.isNumeric(right)
                    if (!otherOk) {
                        diagnostics.error(
                            "字符串拼接需要 String 或数字操作数（S-7.6.1），实际 ${left.render()} 与 ${right.render()}",
                            span.start,
                            ErrorCodes.INVALID_OPERATOR_OPERAND,
                        )
                    }
                    return SemaType.STRING
                }
                arithmeticResult(span, left, right, "加法")
            }

            "-", "*", "/", "%" -> {
                arithmeticResult(span, left, right, "算术")
            }

            "<", ">", "<=", ">=" -> {
                if (!(SemaType.isNumeric(left) && SemaType.isNumeric(right))) {
                    diagnostics.error("比较运算需要数字操作数（S-7.5.1）", span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                SemaType.BOOLEAN
            }

            "==", "!=" -> {
                equalityResult(span, left, right)
            }

            "&&", "||" -> {
                if (!left.isError && !TypeAssignability.sameBase(left, SemaType.BOOLEAN)) {
                    diagnostics.error("'&&'/'||' 需要 Boolean 操作数", span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                if (!right.isError && !TypeAssignability.sameBase(right, SemaType.BOOLEAN)) {
                    diagnostics.error("'&&'/'||' 需要 Boolean 操作数", span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                SemaType.BOOLEAN
            }

            ".." -> {
                SemaType.RANGE
            }

            else -> {
                diagnostics.error("未知运算符 '$op'", span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                SemaType.ErrorT
            }
        }

    private fun equalityResult(
        span: SourceSpan,
        left: SemaType,
        right: SemaType,
    ): SemaType {
        if (!left.isError && !right.isError && !TypeAssignability.sameBase(left, right) && !comparableNull(left, right)) {
            diagnostics.error(
                "'${left.render()}' 与 '${right.render()}' 不可比较（S-7.5.2）",
                span.start,
                ErrorCodes.INVALID_OPERATOR_OPERAND,
            )
        }
        return SemaType.BOOLEAN
    }

    private fun arithmeticResult(
        span: SourceSpan,
        left0: SemaType,
        right0: SemaType,
        what: String,
    ): SemaType {
        val left = SemaType.resolveVar(left0)
        val right = SemaType.resolveVar(right0)
        if (left.isError || right.isError) return SemaType.ErrorT
        if (SemaType.isNumeric(left) && SemaType.isNumeric(right)) {
            val ln = (left as SemaType.Basic).name
            val rn = (right as SemaType.Basic).name
            return when {
                "Double" in listOf(ln, rn) -> SemaType.DOUBLE
                "Float" in listOf(ln, rn) -> SemaType.FLOAT
                "Long" in listOf(ln, rn) -> SemaType.LONG
                else -> SemaType.INT
            }
        }
        diagnostics.error(
            "${what}需要数字操作数（S-7.5.1），实际 ${left.render()} 与 ${right.render()}",
            span.start,
            ErrorCodes.INVALID_OPERATOR_OPERAND,
        )
        return SemaType.ErrorT
    }

    private fun comparableNull(
        left: SemaType,
        right: SemaType,
    ): Boolean = left is SemaType.NothingT || right is SemaType.NothingT

    private fun typeOfUnary(
        expr: YxUnary,
        expected: SemaType?,
    ): SemaType {
        val operand = typeOf(expr.operand)
        if (operand.isError) return SemaType.ErrorT
        return when (expr.op) {
            "!" -> {
                if (!TypeAssignability.sameBase(operand, SemaType.BOOLEAN)) {
                    diagnostics.error("'!' 需要 Boolean 操作数", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                SemaType.BOOLEAN
            }

            "-" -> {
                if (!SemaType.isNumeric(operand)) {
                    diagnostics.error("一元 '-' 需要数字操作数", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                    return SemaType.ErrorT
                }
                // S-7.5.4：Byte 目标 + `-Int 字面量` 按取负后的有效值收窄（-128 合法、-129 非法）
                negatedByteNarrowing(expr, expected) ?: operand
            }

            else -> {
                diagnostics.error("未知一元运算符 '${expr.op}'", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                SemaType.ErrorT
            }
        }
    }

    /** `-Int 字面量` 赋给 Byte：按取负后的值校验范围，避免幅值误判（S-2.5.1/S-7.5.4）。 */
    private fun negatedByteNarrowing(
        expr: YxUnary,
        expected: SemaType?,
    ): SemaType? {
        val expectedNum = expected as? SemaType.Basic ?: return null
        if (expectedNum.nullable || expectedNum.name != "Byte") return null
        val lit = expr.operand as? YxIntLiteral ?: return null
        val clean = lit.text.replace("_", "")
        if (clean.endsWith("L") || clean.endsWith("l")) return null
        val magnitude = parseIntegerLiteral(clean) ?: return null
        if (magnitude > 128) {
            diagnostics.error(
                "字面量 ${-magnitude} 超出 Byte 范围（-128..127）",
                expr.span.start,
                ErrorCodes.TYPE_MISMATCH,
            )
            return SemaType.ErrorT
        }
        return expectedNum
    }

    private fun typeOfLambda(
        expr: YxLambda,
        expected: SemaType?,
    ): SemaType {
        // T-M11-3：期望类型除 SemaType.Function 外，接受 FunctionN Declared（lambda 实参传 FunctionN 参数）
        val expectedFn =
            expected as? SemaType.Function
                ?: (expected as? SemaType.Declared)?.let { functionTypeOfJvm(it) }
        if (expectedFn == null) {
            diagnostics.error("Lambda 参数类型必须由上下文提供（S-4.5.3）", expr.span.start, ErrorCodes.INFERENCE_FAILURE)
            expr.body.let { typeOf(it) }
            return SemaType.ErrorT
        }
        if (expectedFn.params.size != expr.params.size) {
            diagnostics.error(
                "Lambda 实参数量不匹配: 期望 ${expectedFn.params.size} 个，实际 ${expr.params.size} 个",
                expr.span.start,
                ErrorCodes.ARGUMENT_COUNT_MISMATCH,
            )
        }
        smartCast.enterBlock()
        varStack.addLast(mutableMapOf())
        val max = minOf(expectedFn.params.size, expr.params.size)
        for (i in 0 until max) {
            smartCast.shadow(expr.params[i])
            val sym = VariableSymbol(expr.params[i], expectedFn.params[i], isVal = true, expr.span)
            sym.definitelyAssigned = true
            varStack.last()[expr.params[i]] = sym
        }
        // 普通 lambda 不能挂起：体内非 async 上下文（await 报错，T-M14）
        val savedAsyncDepth = asyncContextDepth
        asyncContextDepth = 0
        val bodyType = typeOf(expr.body, expected = expectedFn.ret)
        asyncContextDepth = savedAsyncDepth
        if (!bodyType.isError) inference.expectAssignable(bodyType, expectedFn.ret, expr.body.span.start, "Lambda 返回")
        varStack.removeLast()
        smartCast.exitBlock()
        return expectedFn
    }

    private fun typeOfBlockLambda(
        expr: YxBlockLambda,
        expected: SemaType?,
    ): SemaType {
        // T-M11-3：期望类型除 SemaType.Function 外，接受 FunctionN Declared（块 Lambda 传 FunctionN 参数）
        val expectedFn =
            expected as? SemaType.Function
                ?: (expected as? SemaType.Declared)?.let { functionTypeOfJvm(it) }
        val ret: SemaType = expectedFn?.ret ?: SemaType.UnitT
        smartCast.enterBlock()
        smartCast.shadow("it")
        varStack.addLast(mutableMapOf())
        // 隐式 `it`（S-7.4.2）
        val paramCount = expectedFn?.params?.size ?: 0
        if (paramCount >= 1) {
            val itSym = VariableSymbol("it", expectedFn!!.params[0], isVal = true, expr.span)
            itSym.definitelyAssigned = true
            varStack.last()["it"] = itSym
        }
        // 普通块 Lambda 不能挂起：体内非 async 上下文（await 报错，T-M14）
        val savedAsyncDepth = asyncContextDepth
        asyncContextDepth = 0
        checkStatements(expr.body)
        asyncContextDepth = savedAsyncDepth
        // S-7.4.3：非 Unit 返回时，末条语句必须是表达式且类型匹配（与箭头 Lambda 对称）
        if (SemaType.resolveVar(ret) != SemaType.UnitT) {
            val last = expr.body.statements.lastOrNull()
            when (last) {
                is YxExprStmt -> {
                    val tailType = typeOf(last.expr, expected = ret)
                    if (!tailType.isError) {
                        inference.expectAssignable(tailType, ret, last.expr.span.start, "Lambda 返回")
                    }
                }

                else -> {
                    diagnostics.error(
                        "块 Lambda 返回类型非 Unit 时末条语句必须是表达式（S-7.4.3）",
                        expr.span.start,
                        ErrorCodes.RETURN_TYPE_MISMATCH,
                    )
                }
            }
        }
        varStack.removeLast()
        smartCast.exitBlock()
        return expectedFn ?: SemaType.ErrorT
    }

    // ── 字面量 / 辅助 ───────────────────────────────────────────────────────

    private fun literalIntType(
        text: String,
        expected: SemaType?,
        position: SourcePosition?,
    ): SemaType {
        val clean = text.replace("_", "")
        val isLong = clean.endsWith("L") || clean.endsWith("l")
        val digits = clean.removeSuffix("L").removeSuffix("l")
        val natural = if (isLong) SemaType.LONG else SemaType.INT
        val value = parseIntegerLiteral(clean)
        if (value == null) {
            // 超出 Long 范围：报错而非静默失败/IRGen 崩溃（S-2.5.1）
            diagnostics.error(
                "整数溢出: 字面量 '$text' 超出 Long 范围（-9223372036854775808..9223372036854775807）",
                position,
                ErrorCodes.TYPE_MISMATCH,
            )
            return SemaType.ErrorT
        }
        val isDecimal =
            !digits.startsWith("0x") && !digits.startsWith("0X") &&
                !digits.startsWith("0b") && !digits.startsWith("0B")
        // S-2.5.1：十进制超出 Int 范围自动提升 Long；hex/binary 不提升，超出即报错
        if (!isLong && (value > Int.MAX_VALUE || value < Int.MIN_VALUE)) {
            if (isDecimal) return SemaType.LONG
            diagnostics.error(
                "字面量 $value 超出 Int 范围（-2147483648..2147483647）",
                position,
                ErrorCodes.TYPE_MISMATCH,
            )
            return SemaType.ErrorT
        }
        // 字面量按目标类型收窄（S-7.5.4），超出目标范围报错（S-2.5.1）
        val expectedNum = expected as? SemaType.Basic
        if (!isLong && expectedNum != null && expectedNum.name in SemaType.NUMERIC_NAMES && !expectedNum.nullable) {
            if (expectedNum.name == "Byte" && (value < -128 || value > 127)) {
                diagnostics.error("字面量 $value 超出 Byte 范围（-128..127）", position, ErrorCodes.TYPE_MISMATCH)
                return SemaType.ErrorT
            }
            return expectedNum
        }
        if (isLong && expectedNum != null && expectedNum.name == "Long" && !expectedNum.nullable) {
            return expectedNum
        }
        return natural
    }

    /** 解析整数文本（十/十六/二进制，含后缀）；失败返回 null。 */
    private fun parseIntegerLiteral(text: String): Long? {
        val digits = text.removeSuffix("L").removeSuffix("l")
        return try {
            when {
                digits.startsWith("0x") || digits.startsWith("0X") -> digits.substring(2).toLong(16)
                digits.startsWith("0b") || digits.startsWith("0B") -> digits.substring(2).toLong(2)
                else -> digits.toLong()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun literalFloatType(
        text: String,
        expected: SemaType?,
    ): SemaType {
        val clean = text.replace("_", "")
        val isFloat = clean.endsWith("F") || clean.endsWith("f")
        val natural = if (isFloat) SemaType.FLOAT else SemaType.DOUBLE
        val expectedNum = expected as? SemaType.Basic
        if (!isFloat && expectedNum != null && expectedNum.name == "Float" && !expectedNum.nullable) {
            return expectedNum
        }
        return natural
    }

    private fun elementType(iterable: SemaType): SemaType? =
        when (iterable) {
            is SemaType.Basic -> {
                when (iterable.name) {
                    "Range" -> SemaType.INT
                    "Iterable" -> SemaType.ANY
                    "String" -> SemaType.CHAR
                    "Array" -> SemaType.ANY
                    else -> null
                }
            }

            is SemaType.Declared -> {
                val sym = iterable.symbol
                when {
                    sym is JvmClassSymbol && sym.qualifiedName.startsWith("[") -> classPath.arrayElementType(sym.qualifiedName)
                    sym is JvmClassSymbol && sym.qualifiedName == "java.lang.Iterable" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                    sym is JvmClassSymbol && sym.qualifiedName == "java.util.List" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                    sym is JvmClassSymbol && sym.qualifiedName == "java.util.Set" -> iterable.args.getOrNull(0) ?: SemaType.ANY
                    sym is JvmClassSymbol && sym.isIterable() -> iterable.args.getOrNull(0) ?: SemaType.ANY
                    sym is YxClassSymbol && iterable.args.isNotEmpty() -> iterable.args[0]
                    else -> null
                }
            }

            is SemaType.InferenceVar -> {
                iterable.solution?.let { elementType(it) }
            }

            else -> {
                null
            }
        }

    /**
     * JVM FunctionN 声明类型 → Yux 函数类型（T-M11-3）：按后缀数字取 arity，
     * 泛型实参（反射裸类型为空）缺省为 Any。
     */
    private fun functionTypeOfJvm(declared: SemaType.Declared): SemaType.Function? {
        val sym = declared.symbol as? JvmClassSymbol ?: return null
        val qn = sym.qualifiedName
        val arity =
            when {
                qn == "yux.core.function.Function0" -> 0
                qn == "yux.core.function.Function1" -> 1
                qn == "yux.core.function.Function2" -> 2
                qn == "yux.core.function.Function3" -> 3
                else -> return null
            }
        val params = (0 until arity).map { declared.args.getOrNull(it) ?: SemaType.ANY }
        val ret = declared.args.getOrNull(arity) ?: SemaType.ANY
        return SemaType.Function(params, ret, nullable = false)
    }

    private fun functionType(fn: FunctionSymbol): SemaType =
        SemaType.Function(fn.params.map { it.type }, fn.returnType ?: SemaType.UnitT, nullable = false)

    private fun functionType(m: JvmMethodSymbol): SemaType = SemaType.Function(m.params, m.returnType, nullable = false)

    // ── 作用域查找 ──────────────────────────────────────────────────────────

    private fun lookupVariable(name: String): VariableSymbol? {
        for (i in varStack.indices.reversed()) {
            varStack[i][name]?.let { return it }
        }
        return null
    }

    private fun resolveFunctions(name: String): List<FunctionSymbol> {
        // 沿类/父类链解析（S-8.7.1）：复用 Symbols.kt 的 functionsIncludingSuper（接口成员
        // 继承由该辅助实现），避免本文件再维护一份父类链遍历
        val result = mutableListOf<FunctionSymbol>()
        currentClass?.let { result += it.functionsIncludingSuper(name) }
        val file = currentFile
        if (file != null) {
            file.topLevelFunctions[name]?.let { result += it }
            for (other in symbolTable.files) {
                if (other !== file && other.packageName == file.packageName) {
                    other.topLevelFunctions[name]?.let { result += it }
                }
            }
        }
        if (result.isEmpty()) {
            BuiltinFunctions.find(name)?.let { result += it }
        }
        return result
    }

    private fun superClassOf(cls: YxClassSymbol): YxClassSymbol? = (cls.superType as? SemaType.Declared)?.symbol as? YxClassSymbol
}

/**
 * 内置函数（yux.core 最小集，01-§10.1）：标准库尚未实现前由语义层直接提供，
 * 确保 `print "..."` 等示例可分析通过。
 *
 * serialize/deserialize（S-8.3）映射 yux.serializer.YuxSerializer；print/println 映射 yux.core.CoreLib。
 */
object BuiltinFunctions {
    private val ANY = SemaType.ANY
    private val STRING = SemaType.STRING
    private val UNIT = SemaType.UnitT

    private val functions =
        mapOf(
            "print" to
                FunctionSymbol(
                    "print",
                    listOf(ParameterSymbol("x", ANY, hasDefault = false, null)),
                    UNIT,
                    false,
                    false,
                    null,
                    null,
                    YxVisibility.PUBLIC,
                    null,
                    null,
                ),
            "println" to
                FunctionSymbol(
                    "println",
                    listOf(ParameterSymbol("x", ANY, hasDefault = false, null)),
                    UNIT,
                    false,
                    false,
                    null,
                    null,
                    YxVisibility.PUBLIC,
                    null,
                    null,
                ),
            "serialize" to
                FunctionSymbol(
                    "serialize",
                    listOf(ParameterSymbol("x", ANY, hasDefault = false, null)),
                    STRING,
                    false,
                    false,
                    null,
                    null,
                    YxVisibility.PUBLIC,
                    null,
                    null,
                ),
            "deserialize" to
                FunctionSymbol(
                    "deserialize",
                    listOf(
                        ParameterSymbol("text", STRING, hasDefault = false, null),
                        ParameterSymbol("type", ANY, hasDefault = false, null),
                    ),
                    ANY,
                    false,
                    false,
                    null,
                    null,
                    YxVisibility.PUBLIC,
                    null,
                    null,
                ),
        )

    fun find(name: String): FunctionSymbol? = functions[name]

    /** 内置函数 → 运行时宿主（与 IRGen builtinCall 的路由一致）。 */
    fun ownerOf(name: String): String? =
        when (name) {
            "print", "println" -> "yux.core.CoreLib"
            "serialize", "deserialize" -> "yux.serializer.YuxSerializer"
            else -> null
        }
}
