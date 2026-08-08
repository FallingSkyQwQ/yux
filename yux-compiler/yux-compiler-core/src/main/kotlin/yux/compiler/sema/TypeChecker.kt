package yux.compiler.sema

import yux.compiler.ast.YxAccessor
import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAsyncBlock
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
import yux.compiler.ast.YxInitBlock
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxIsCondition
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNode
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxParallelBlock
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
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhile
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
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
    private var loopDepth = 0

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
            for (fns in file.topLevelFunctions.values) {
                for (fn in fns) fn.decl?.let { checkFunction(file, fn, it) }
            }
            for (prop in file.topLevelProperties.values) {
                prop.decl?.let { checkTopLevelProperty(file, prop, it) }
            }
            currentFile = null
        }
    }

    // ── 声明 ────────────────────────────────────────────────────────────────

    private fun checkClass(file: FileScope, sym: YxClassSymbol) {
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
    private fun classInitBlocks(file: FileScope, sym: YxClassSymbol): List<YxBlock> {
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

    private fun checkProperty(file: FileScope, owner: YxClassSymbol, prop: PropertySymbol, decl: YxProperty) {
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

    private fun checkAccessor(file: FileScope, propertyType: SemaType, accessor: YxAccessor) {
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
                val ret: SemaType = when {
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

    private fun checkTopLevelProperty(file: FileScope, prop: PropertySymbol, decl: YxProperty) {
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

    private fun checkFunction(file: FileScope, fn: FunctionSymbol, decl: YxFunction) {
        // 预扫赋值目标：决定智能转型是否允许（02-§7.3）
        val reassigned = AstScan.assignmentTargetNames(decl.body)
        enterFunctionScope(reassigned)
        fn.params.forEach { p ->
            varStack.last()[p.name] = VariableSymbol(p.name, p.type, isVal = true, p.span).also { it.definitelyAssigned = true }
        }
        // 返回类型：未声明 → 推断变量（S-5.5.2），块体无返回 → Unit
        val declaredReturn = fn.returnType ?: inference.freshVar().also { fn.returnType = it }
        currentReturnType = declaredReturn
        val bodyType: SemaType? = when (val body = decl.body) {
            is YxFunctionBody.YxExpressionBody -> {
                val t = typeOf(body.expr, expected = declaredReturn)
                inference.expectReturn(t, declaredReturn, body.span.start, "函数 '${fn.name}'")
            }
            is YxFunctionBody.YxBlockBody -> {
                checkStatements(body.block)
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
        // 返回类型求解：表达式体取表达式类型；块体未显式返回 → Unit（S-5.5.2）
        if (declaredReturn is SemaType.InferenceVar && declaredReturn.solution == null) {
            declaredReturn.solution = bodyType ?: SemaType.UnitT
        }
        declTypes[decl] = (declaredReturn as? SemaType.InferenceVar)?.solution ?: declaredReturn
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
            is YxVarDecl -> checkVarDecl(stmt)
            is YxAssign -> checkAssign(stmt)
            is YxExprStmt -> {
                typeOf(stmt.expr)
            }
            is YxIf -> checkIf(stmt)
            is YxWhen -> checkWhen(stmt)
            is YxFor -> checkFor(stmt)
            is YxWhile -> checkWhile(stmt)
            is YxReturn -> checkReturn(stmt)
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
            is YxTry -> checkTry(stmt)
            is YxAsyncBlock -> checkStatements(stmt.body)
            is YxParallelBlock -> checkStatements(stmt.body)
            is YxUnsafeBlock -> checkStatements(stmt.body)
        }
    }

    private fun checkVarDecl(stmt: YxVarDecl) {
        val existing = lookupVariable(stmt.name)
        if (existing != null) {
            // S-6.1.1：已在作用域链声明 → 赋值语义
            existing.reassigned = true
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
        val valueType = typeOf(stmt.value, expected = expectedAssignTargetType(stmt.target))
        when (val target = stmt.target) {
            is YxIdentifier -> {
                val sym = lookupVariable(target.name)
                if (sym == null) {
                    diagnostics.error("未解析的变量 '${target.name}'", stmt.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
                    return
                }
                sym.reassigned = true
                if (sym.isVal) {
                    diagnostics.error("只读变量 '${target.name}' 不可赋值（S-6.1.1）", stmt.span.start, ErrorCodes.VAL_ASSIGNMENT)
                }
                if (sym.type != null && !valueType.isError) {
                    inference.expectAssignable(valueType, sym.type!!, stmt.span.start, "赋值 '${target.name}'")
                }
                sym.definitelyAssigned = true
                resolvedRefs[target] = sym
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
            else -> diagnostics.error("赋值目标不合法（S-6.1.1）", stmt.span.start, ErrorCodes.ILLEGAL_ASSIGN_TARGET)
        }
    }

    /** 赋值目标的期望类型（供字面量/null 收窄，S-7.5.4/S-8.1）。 */
    private fun expectedAssignTargetType(target: YxExpr): SemaType? = when (target) {
        is YxIdentifier -> lookupVariable(target.name)?.type
        is YxMemberAccess -> memberLookup(typeOf(target.receiver), target.name)?.let { (t, _, _) -> t }
        else -> null
    }

    /** 成员可写性：null=不存在；false=只读；SemaType=可写且目标类型。 */
    private fun memberWritability(receiver: SemaType, name: String): Any? = when (receiver) {
        is SemaType.Declared -> {
            val sym = receiver.symbol
            when (sym) {
                is YxClassSymbol -> {
                    val p = sym.property(name) ?: return null
                    if (p.isVal) false else p.type ?: SemaType.ErrorT
                }
                is JvmClassSymbol -> {
                    if (sym.propertyType(name) == null) null
                    else if (!sym.propertySettable(name)) false
                    else sym.propertyType(name)
                }
                else -> null
            }
        }
        is SemaType.Basic -> {
            if (jvmForBasic(receiver.name) == null) null
            else memberWritability(jvmForBasic(receiver.name)!!.let { SemaType.Declared(it, emptyList(), receiver.nullable) }, name)
        }
        else -> null
    }

    private fun checkIf(stmt: YxIf) {
        val condType = typeOf(stmt.condition)
        if (!condType.isError && !TypeAssignability.sameBase(condType, SemaType.BOOLEAN)) {
            diagnostics.error("if 条件必须是 Boolean（S-6.2.1），实际 ${condType.render()}", stmt.condition.span.start, ErrorCodes.CONDITION_NOT_BOOLEAN)
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
    private fun mergeDefiniteAssignment(before: Map<String, Boolean>, thenS: Map<String, Boolean>, elseS: Map<String, Boolean>, hasElse: Boolean) {
        for (i in varStack.indices.reversed()) {
            for ((name, sym) in varStack[i]) {
                val b = before[name] ?: false
                val inThen = thenS[name] ?: b
                val inElse = if (hasElse) (elseS[name] ?: b) else b
                sym.definitelyAssigned = b || (inThen && inElse)
            }
        }
    }

    private fun applyConditionCasts(condition: YxExpr, thenBranch: Boolean) {
        if (condition is YxIs) {
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
            if (!smartCast.applyIsCast(name, resolved, ::isReassigned)) {
                diagnostics.remind(
                    "可变引用 '$name' 的智能转型被抑制（02-§7.3）",
                    condition.span.start,
                    ErrorCodes.SMART_CAST_MUTABLE,
                )
            }
            return
        }
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
        val subjectType = typeOf(stmt.subject)
        for (branch in stmt.branches) {
            smartCast.enterBlock()
            when (val cond = branch.condition) {
                is YxIsCondition -> {
                    val resolved = typeResolver.resolve(cond.type, currentFile!!)
                    val name = subjectAsVariable(stmt.subject)
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
                is YxElseCondition -> {
                    // else 分支：subject == null 时 subject 为非空
                    if (subjectType.nullable) {
                        val name = subjectAsVariable(stmt.subject)
                        if (name != null) smartCast.register(name, subjectType.nonNull())
                    }
                }
            }
            checkStatement(branch.body)
            smartCast.exitBlock()
        }
    }

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
            diagnostics.error("while 条件必须是 Boolean（S-6.2.1），实际 ${condType.render()}", stmt.condition.span.start, ErrorCodes.CONDITION_NOT_BOOLEAN)
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
        fun scan(stmt: YxStmt): Boolean = when (stmt) {
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
        val allCatches = if (catchStates.isEmpty()) {
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

    private fun typeOf(expr: YxExpr, expected: SemaType? = null): SemaType {
        val t = computeType(expr, expected)
        exprTypes[expr] = t
        return t
    }

    private fun computeType(expr: YxExpr, expected: SemaType?): SemaType = when (expr) {
        is YxIntLiteral -> literalIntType(expr.text, expected)
        is YxFloatLiteral -> literalFloatType(expr.text, expected)
        is YxCharLiteral -> SemaType.CHAR
        is YxBoolLiteral -> SemaType.BOOLEAN
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
                else -> expected.asNullable()
            }
        }
        is YxStringLiteral -> SemaType.STRING
        is YxRawStringLiteral -> SemaType.STRING
        is YxStringText -> SemaType.STRING
        is YxStringTemplate -> {
            expr.parts.forEach { typeOf(it, expected = SemaType.STRING) }
            SemaType.STRING
        }
        is YxIdentifier -> typeOfIdentifier(expr)
        is YxMemberAccess -> typeOfMemberAccess(expr)
        is YxCall -> typeOfCall(expr)
        is YxTypeCall -> typeOfTypeCall(expr)
        is YxTypeReference -> {
            val resolved = typeResolver.resolve(expr.type, currentFile!!)
            if (resolved.isError) SemaType.ErrorT else resolved
        }
        is YxBinary -> typeOfBinary(expr, expected)
        is YxUnary -> typeOfUnary(expr)
        is YxLambda -> typeOfLambda(expr, expected)
        is YxBlockLambda -> typeOfBlockLambda(expr, expected)
        is YxParen -> typeOf(expr.expr, expected)
        is YxThrow -> {
            typeOf(expr.expr)
            SemaType.NothingT
        }
        is YxIs -> {
            typeOf(expr.expr)
            SemaType.BOOLEAN
        }
        is YxAs -> {
            typeOf(expr.expr)
            typeResolver.resolve(expr.type, currentFile!!)
        }
        is YxNullable -> typeOf(expr.expr).asNullable()
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
            if (currentClass == null) {
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
        // 类成员属性（隐式 this，S-5.2）
        currentClass?.property(expr.name)?.let { prop ->
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
        val (type, isPropertyRead, _) = result
        if (isPropertyRead) nullGuard.checkMemberRead(expr, receiverType, isPropertyRead = true)
        return type
    }

    private fun staticMemberType(receiverType: SemaType, name: String, node: YxMemberAccess): SemaType {
        val sym = (receiverType as? SemaType.Declared)?.symbol ?: (receiverType as? SemaType.Basic)?.let { jvmForBasic(it.name) }
        if (sym is JvmClassSymbol) {
            sym.fields.firstOrNull { it.name == name && it.isStatic }?.let { return it.type }
            sym.propertyType(name)?.let { return it }
            sym.staticMethods.firstOrNull { it.name == name }?.let { return functionType(it) }
        }
        diagnostics.error("静态成员 '${name}' 不存在", node.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    /** 成员查找：返回 (类型, 是否属性读取, 引用符号)。 */
    private fun memberLookup(receiver: SemaType, name: String): Triple<SemaType, Boolean, Symbol?>? = when (receiver) {
        is SemaType.Declared -> {
            val sym = receiver.symbol
            when (sym) {
                is YxClassSymbol -> {
                    sym.property(name)?.let { return Triple(it.type ?: SemaType.ErrorT, true, it) }
                    sym.functionsNamed(name).firstOrNull()?.let { return Triple(functionType(it), false, it) }
                    null
                }
                is JvmClassSymbol -> {
                    sym.propertyType(name)?.let { return Triple(it, true, null) }
                    sym.methodNamed(name)?.let { return Triple(functionType(it), false, null) }
                    null
                }
                else -> null
            }
        }
        is SemaType.Basic -> {
            if (receiver.name == "String" && name == "length") return Triple(SemaType.INT, true, null)
            jvmForBasic(receiver.name)?.let { jc ->
                jc.propertyType(name)?.let { return Triple(it, true, null) }
            }
            null
        }
        is SemaType.TypeParam -> null
        is SemaType.Function -> null
        is SemaType.InferenceVar -> receiver.solution?.let { memberLookup(it, name) }
        else -> null
    }

    private fun jvmForBasic(name: String): JvmClassSymbol? = when (name) {
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
        "Range" -> classPath.resolve("java.lang.Integer")
        else -> null
    }

    // ── 调用 ────────────────────────────────────────────────────────────────

    private fun typeOfCall(call: YxCall): SemaType = when (val callee = call.callee) {
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
                checkCallable(fns, call, "函数 '${callee.name}'")
            }
        }
        is YxMemberAccess -> {
            val receiverType = typeOf(callee.receiver)
            if (receiverType.isError) return SemaType.ErrorT
            val result = if (callee.receiver is YxTypeReference) {
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
        is YxParen -> applyFunctionType(typeOf(callee), call)
        is YxLambda -> {
            diagnostics.error("Lambda 不可直接调用", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
            SemaType.ErrorT
        }
        else -> applyFunctionType(typeOf(callee), call)
    }

    private fun applyFunctionType(type: SemaType, call: YxCall): SemaType {
        val fn = type as? SemaType.Function
        if (fn == null) {
            diagnostics.error("'${type.render()}' 不可调用", call.span.start, ErrorCodes.UNRESOLVED_REFERENCE)
            return SemaType.ErrorT
        }
        return checkFunctionCallArgs(fn.params, fn.ret, call)
    }

    private fun checkFunctionCallArgs(params: List<SemaType>, ret: SemaType, call: YxCall): SemaType {
        if (call.args.size != params.size) {
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

    private fun checkInstanceCall(receiverType: SemaType, name: String, call: YxCall): SemaType {
        val receiver = receiverType
        if (receiver is SemaType.Declared && receiver.symbol is YxClassSymbol) {
            val sym = receiver.symbol
            val fns = sym.functionsNamed(name)
            if (fns.isEmpty()) {
                val prop = sym.property(name)
                if (prop != null) {
                    return applyFunctionType(prop.type ?: SemaType.ErrorT, call)
                }
                diagnostics.error("方法 '${name}' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
                return SemaType.ErrorT
            }
            return checkCallable(fns, call, "方法 '${name}'")
        }
        if (receiver is SemaType.Declared && receiver.symbol is JvmClassSymbol) {
            val sym = receiver.symbol
            val methods = sym.methods.filter { it.name == name }
            if (methods.isEmpty()) {
                sym.propertyType(name)?.let { return applyFunctionType(it, call) }
                diagnostics.error("方法 '${name}' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
                return SemaType.ErrorT
            }
            return checkJvmCall(methods, call)
        }
        if (receiver is SemaType.Basic) {
            val jc = jvmForBasic(receiver.name)
            if (jc != null) {
                val methods = jc.methods.filter { it.name == name }
                if (methods.isNotEmpty()) return checkJvmCall(methods, call)
            }
        }
        if (receiver is SemaType.Function) {
            return applyFunctionType(receiver, call)
        }
        diagnostics.error("方法 '${name}' 不存在于 '${receiverType.render()}'", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    private fun checkStaticCall(receiverType: SemaType, name: String, call: YxCall): SemaType {
        val sym = (receiverType as? SemaType.Declared)?.symbol ?: (receiverType as? SemaType.Basic)?.let { jvmForBasic(it.name) }
        if (sym is JvmClassSymbol) {
            val methods = sym.staticMethods.filter { it.name == name }
            if (methods.isNotEmpty()) return checkJvmCall(methods, call)
            sym.fields.firstOrNull { it.name == name && it.isStatic }?.let { return applyFunctionType(it.type, call) }
        }
        diagnostics.error("静态方法 '${name}' 不存在", call.span.start, ErrorCodes.UNRESOLVED_MEMBER)
        return SemaType.ErrorT
    }

    private fun checkJvmCall(methods: List<JvmMethodSymbol>, call: YxCall): SemaType {
        val byArity = methods.filter { it.params.size == call.args.size }
        val candidates = byArity.ifEmpty { methods }
        // 重载选择：按实参类型可赋值性优先（避免反射方法顺序不确定性）
        val argTypes = call.args.map { typeOf(it) }
        val matched = candidates.firstOrNull { m ->
            m.params.indices.all { i -> TypeAssignability.isAssignable(argTypes[i], m.params[i]) }
        } ?: candidates.first()
        return checkFunctionCallArgs(matched.params, matched.returnType, call)
    }

    private fun checkCallable(candidates: List<FunctionSymbol>, call: YxCall, kind: String): SemaType {
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

    private fun canCallWith(fn: FunctionSymbol, argCount: Int): Boolean {
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
        val sym = resolved.symbol
        val constructorParams: List<SemaType> = when (sym) {
            is JvmClassSymbol -> {
                val ctor = sym.constructors.firstOrNull { it.params.size == call.args.size } ?: sym.constructors.firstOrNull()
                ctor?.params ?: emptyList()
            }
            is YxClassSymbol -> sym.properties().map { it.type ?: SemaType.ErrorT }
            else -> emptyList()
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
        return resolved
    }

    // ── 运算符 / Lambda ─────────────────────────────────────────────────────

    private fun typeOfBinary(expr: YxBinary, expected: SemaType?): SemaType {
        // `x == null` / `null == x`：null 字面量以对侧类型为期望（S-8.1）
        if (expr.op == "==" || expr.op == "!=") {
            if (expr.right is YxNullLiteral) {
                val left = typeOf(expr.left)
                val right = typeOf(expr.right, expected = left)
                return equalityResult(expr, left, right)
            }
            if (expr.left is YxNullLiteral) {
                val right = typeOf(expr.right)
                val left = typeOf(expr.left, expected = right)
                return equalityResult(expr, left, right)
            }
        }
        val left = typeOf(expr.left)
        val right = typeOf(expr.right)
        when (expr.op) {
            "+" -> {
                if (left is SemaType.Basic && left.name == "String" || right is SemaType.Basic && right.name == "String") {
                    return SemaType.STRING
                }
                return arithmeticResult(expr, left, right, "加法")
            }
            "-", "*", "/", "%" -> return arithmeticResult(expr, left, right, "算术")
            "<", ">", "<=", ">=" -> {
                if (!(SemaType.isNumeric(left) && SemaType.isNumeric(right))) {
                    diagnostics.error("比较运算需要数字操作数（S-7.5.1）", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                return SemaType.BOOLEAN
            }
            "==", "!=" -> return equalityResult(expr, left, right)
            "&&", "||" -> {
                if (!left.isError && !TypeAssignability.sameBase(left, SemaType.BOOLEAN)) {
                    diagnostics.error("'&&'/'||' 需要 Boolean 操作数", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                if (!right.isError && !TypeAssignability.sameBase(right, SemaType.BOOLEAN)) {
                    diagnostics.error("'&&'/'||' 需要 Boolean 操作数", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                }
                return SemaType.BOOLEAN
            }
            ".." -> return SemaType.RANGE
            else -> {
                diagnostics.error("未知运算符 '${expr.op}'", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                return SemaType.ErrorT
            }
        }
    }

    private fun equalityResult(expr: YxBinary, left: SemaType, right: SemaType): SemaType {
        if (!left.isError && !right.isError && !TypeAssignability.sameBase(left, right) && !comparableNull(left, right)) {
            diagnostics.error(
                "'${left.render()}' 与 '${right.render()}' 不可比较（S-7.5.2）",
                expr.span.start,
                ErrorCodes.INVALID_OPERATOR_OPERAND,
            )
        }
        return SemaType.BOOLEAN
    }

    private fun arithmeticResult(expr: YxBinary, left0: SemaType, right0: SemaType, what: String): SemaType {
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
        diagnostics.error("${what}需要数字操作数（S-7.5.1），实际 ${left.render()} 与 ${right.render()}", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
        return SemaType.ErrorT
    }

    private fun comparableNull(left: SemaType, right: SemaType): Boolean =
        left is SemaType.NothingT || right is SemaType.NothingT

    private fun typeOfUnary(expr: YxUnary): SemaType {
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
                }
                operand
            }
            else -> {
                diagnostics.error("未知一元运算符 '${expr.op}'", expr.span.start, ErrorCodes.INVALID_OPERATOR_OPERAND)
                SemaType.ErrorT
            }
        }
    }

    private fun typeOfLambda(expr: YxLambda, expected: SemaType?): SemaType {
        val expectedFn = expected as? SemaType.Function
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
            val sym = VariableSymbol(expr.params[i], expectedFn.params[i], isVal = true, expr.span)
            sym.definitelyAssigned = true
            varStack.last()[expr.params[i]] = sym
        }
        val bodyType = typeOf(expr.body, expected = expectedFn.ret)
        if (!bodyType.isError) inference.expectAssignable(bodyType, expectedFn.ret, expr.body.span.start, "Lambda 返回")
        varStack.removeLast()
        smartCast.exitBlock()
        return expectedFn
    }

    private fun typeOfBlockLambda(expr: YxBlockLambda, expected: SemaType?): SemaType {
        val expectedFn = expected as? SemaType.Function
        val ret: SemaType = expectedFn?.ret ?: SemaType.UnitT
        smartCast.enterBlock()
        varStack.addLast(mutableMapOf())
        // 隐式 `it`（S-7.4.2）
        val paramCount = expectedFn?.params?.size ?: 0
        if (paramCount >= 1) {
            val itSym = VariableSymbol("it", expectedFn!!.params[0], isVal = true, expr.span)
            itSym.definitelyAssigned = true
            varStack.last()["it"] = itSym
        }
        checkStatements(expr.body)
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
                else -> diagnostics.error(
                    "块 Lambda 返回类型非 Unit 时末条语句必须是表达式（S-7.4.3）",
                    expr.span.start,
                    ErrorCodes.RETURN_TYPE_MISMATCH,
                )
            }
        }
        varStack.removeLast()
        smartCast.exitBlock()
        return expectedFn ?: SemaType.ErrorT
    }

    // ── 字面量 / 辅助 ───────────────────────────────────────────────────────

    private fun literalIntType(text: String, expected: SemaType?): SemaType {
        val clean = text.replace("_", "")
        val isLong = clean.endsWith("L") || clean.endsWith("l")
        val natural = if (isLong) SemaType.LONG else SemaType.INT
        val value = parseIntegerLiteral(clean) ?: return SemaType.ErrorT
        // 字面量按目标类型收窄（S-7.5.4），超出目标范围报错（S-2.5.1）
        val expectedNum = expected as? SemaType.Basic
        if (!isLong && expectedNum != null && expectedNum.name in SemaType.NUMERIC_NAMES && !expectedNum.nullable) {
            if (expectedNum.name == "Byte" && (value < -128 || value > 127)) {
                diagnostics.error("字面量 $value 超出 Byte 范围（-128..127）", null, ErrorCodes.TYPE_MISMATCH)
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

    private fun literalFloatType(text: String, expected: SemaType?): SemaType {
        val clean = text.replace("_", "")
        val isFloat = clean.endsWith("F") || clean.endsWith("f")
        val natural = if (isFloat) SemaType.FLOAT else SemaType.DOUBLE
        val expectedNum = expected as? SemaType.Basic
        if (!isFloat && expectedNum != null && expectedNum.name == "Float" && !expectedNum.nullable) {
            return expectedNum
        }
        return natural
    }

    private fun elementType(iterable: SemaType): SemaType? = when (iterable) {
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
                sym is YxClassSymbol && iterable.args.isNotEmpty() -> iterable.args[0]
                else -> null
            }
        }
        is SemaType.InferenceVar -> iterable.solution?.let { elementType(it) }
        else -> null
    }

    private fun functionType(fn: FunctionSymbol): SemaType =
        SemaType.Function(fn.params.map { it.type }, fn.returnType ?: SemaType.UnitT, nullable = false)

    private fun functionType(m: JvmMethodSymbol): SemaType =
        SemaType.Function(m.params, m.returnType, nullable = false)

    // ── 作用域查找 ──────────────────────────────────────────────────────────

    private fun lookupVariable(name: String): VariableSymbol? {
        for (i in varStack.indices.reversed()) {
            varStack[i][name]?.let { return it }
        }
        return null
    }

    private fun resolveFunctions(name: String): List<FunctionSymbol> {
        val result = mutableListOf<FunctionSymbol>()
        var cls = currentClass
        while (cls != null) {
            result += cls.functionsNamed(name)
            cls = superClassOf(cls)
        }
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

    private fun superClassOf(cls: YxClassSymbol): YxClassSymbol? =
        (cls.superType as? SemaType.Declared)?.symbol as? YxClassSymbol
}

/**
 * 内置函数（yux.core 最小集，01-§10.1）：标准库尚未实现前由语义层直接提供，
 * 确保 `print "..."` 等示例可分析通过。
 */
object BuiltinFunctions {
    private val ANY = SemaType.ANY
    private val STRING = SemaType.STRING
    private val UNIT = SemaType.UnitT

    private val functions = mapOf(
        "print" to FunctionSymbol("print", listOf(ParameterSymbol("x", ANY, hasDefault = false, null)), UNIT, false, false, null, null),
        "println" to FunctionSymbol("println", listOf(ParameterSymbol("x", ANY, hasDefault = false, null)), UNIT, false, false, null, null),
        "serialize" to FunctionSymbol("serialize", listOf(ParameterSymbol("x", ANY, hasDefault = false, null)), STRING, false, false, null, null),
    )

    fun find(name: String): FunctionSymbol? = functions[name]
}
