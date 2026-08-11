package yux.compiler.irgen

import yux.compiler.ast.YxVisibility
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCatch
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.YxClassSymbol

/**
 * async fun → JVM 状态机的核心降级器（T-M14，完整 CPS）：
 *
 * 1. 把门面方法体的扁平语句列表按「跳转目标标签 / 挂起点」划分为状态块；
 * 2. 局部变量/参数提升为状态机字段；`this` 捕获到 receiver 字段；
 * 3. `invokeSuspend` 按 state 字段分派到各状态块；await 结束当前块并返回 SUSPENDED；
 * 4. try/catch/finally 跨挂起点：异常路由到 catch 分派状态，finally 按
 *    正常/异常/返回三路径展开；
 * 5. 门面（返回 Task）+ 挂起入口（续延传递）由状态机驱动生成。
 */
internal class StateMachineGenerator(
    private val owner: IrClass,
    private val facade: IrMethod,
    private val suspendEntry: IrMethod,
    private val lowering: AsyncCpsLowering,
    private val module: IrModule,
) {

    // ── 状态机类骨架 ─────────────────────────────────────────────────────────

    private val smName: String = lowering.smClassName(owner, facade)
    private val smSymbol: YxClassSymbol = YxClassSymbol(
        name = smName,
        typeParams = emptyList(),
        isData = false,
        isService = false,
        superType = null,
        interfaces = emptyList(),
        members = mutableListOf(),
        isAbstract = false,
        isSealed = false,
        annotations = emptyList(),
        visibility = YxVisibility.PUBLIC,
        fileScope = null,
        span = null,
    )
    private val smType: IrType = IrType.Declared(smSymbol, emptyList())

    private val smClass = IrClass(
        name = smName,
        isFileClass = false,
        isData = false,
        isService = false,
        superType = null,
        interfaces = listOf(lowering.continuationType, lowering.suspendableType),
    )

    private val stateField = IrField("state", IrType.INT, isStatic = false, isFinal = false)
    private val receiverField = IrField("receiver", IrType.ANY, isStatic = false, isFinal = false)
    private val completionField = IrField("completion", lowering.continuationType, isStatic = false, isFinal = false)
    private val resultField = IrField("result", IrType.ANY, isStatic = false, isFinal = false)
    private val exceptionField = IrField("exception", IrType.ANY, isStatic = false, isFinal = false)
    private val rethrownField = IrField("rethrown", IrType.ANY, isStatic = false, isFinal = false)
    private val returnValueField = IrField("returnValue", IrType.ANY, isStatic = false, isFinal = false)

    private val localFields = mutableMapOf<IrLocal, IrField>()
    private var actualReturnState = -1

    /** 顶层流正常结束的续延哨兵：processFlow 时实际返回状态尚未创建，生成时解析。 */
    private val ACTUAL_RETURN = -2

    // ── 状态划分 ─────────────────────────────────────────────────────────────

    private class State(
        val index: Int,
        val code: MutableList<IrStmt> = mutableListOf(),
        var successor: Int = -1,
        var context: TryRegion? = null,
        var isContinuation: Boolean = false,
        /** 当前状态以 Await 结束时，挂起后恢复的状态。 */
        var suspendContinuation: Int = -1,
        /** 续延状态进入时把 result 字段绑定到的局部。 */
        var resultBind: IrLocal? = null,
    )

    private class TryRegion(
        val dispatchState: Int,
        val outer: TryRegion?,
        val finallyBody: List<IrStmt>?,
    ) {
        var returnStateCache: Int = -1
        var excStateCache: Int = -1
    }

    private val states = mutableListOf<State>()
    private val labelToState = mutableMapOf<IrLabel, Int>()

    private fun newState(context: TryRegion?, isContinuation: Boolean): Int {
        states += State(states.size, context = context, isContinuation = isContinuation)
        return states.lastIndex
    }

    private fun stateLabel(index: Int): IrLabel = IrLabel("state$index")

    /** 状态后继解析（ACTUAL_RETURN 哨兵 → 实际返回状态）。 */
    private fun successorOf(s: State): Int =
        if (s.successor == ACTUAL_RETURN) actualReturnState else s.successor

    // ── 局部收集与跳转目标 ───────────────────────────────────────────────────

    private fun collectLocalRefs(): Set<IrLocal> {
        val out = LinkedHashSet<IrLocal>()
        facade.paramLocals.forEach { out += it }
        fun collectExpr(e: IrExpr?) {
            when (e) {
                is IrExpr.LocalRead -> out += e.local
                is IrExpr.FieldRead -> collectExpr(e.receiver)
                is IrExpr.New -> e.args.forEach { collectExpr(it) }
                is IrExpr.ArrayLoad -> {
                    collectExpr(e.base)
                    collectExpr(e.index)
                }
                is IrExpr.Arith -> {
                    collectExpr(e.l)
                    collectExpr(e.r)
                }
                is IrExpr.Compare -> {
                    collectExpr(e.l)
                    collectExpr(e.r)
                }
                is IrExpr.Not -> collectExpr(e.operand)
                is IrExpr.Neg -> collectExpr(e.operand)
                is IrExpr.Convert -> collectExpr(e.expr)
                is IrExpr.IsType -> collectExpr(e.expr)
                is IrExpr.Invoke -> {
                    collectExpr(e.receiver)
                    e.args.forEach { collectExpr(it) }
                }
                is IrExpr.FnInvoke -> {
                    collectExpr(e.fn)
                    e.args.forEach { collectExpr(it) }
                }
                is IrExpr.StringTemplate -> e.parts.forEach { collectExpr(it) }
                is IrExpr.NullGuard -> collectExpr(e.expr)
                is IrExpr.Lambda -> e.captures.forEach { collectExpr(it) }
                else -> Unit
            }
        }
        fun collectStmt(stmt: IrStmt) {
            when (stmt) {
                is IrStmt.LocalAssign -> {
                    out += stmt.local
                    collectExpr(stmt.value)
                }
                is IrStmt.Await -> {
                    out += stmt.local
                    collectExpr(stmt.target)
                }
                is IrStmt.Call -> {
                    collectExpr(stmt.receiver)
                    stmt.args.forEach { collectExpr(it) }
                }
                is IrStmt.New -> stmt.args.forEach { collectExpr(it) }
                is IrStmt.Eval -> collectExpr(stmt.expr)
                is IrStmt.FieldAccess -> {
                    collectExpr(stmt.receiver)
                    collectExpr(stmt.value)
                }
                is IrStmt.Branch -> collectExpr(stmt.cond)
                is IrStmt.Return -> collectExpr(stmt.value)
                is IrStmt.Throw -> collectExpr(stmt.value)
                is IrStmt.Monitor -> collectExpr(stmt.expr)
                is IrStmt.Try -> {
                    stmt.body.forEach { collectStmt(it) }
                    stmt.catches.forEach { c ->
                        c.body.forEach { collectStmt(it) }
                        findLocalInList(c.body, c.paramName)?.let { out += it }
                    }
                    stmt.finallyBody?.forEach { collectStmt(it) }
                }
                else -> Unit
            }
        }
        facade.body.forEach { collectStmt(it) }
        return out
    }

    private fun findLocalInList(stmts: List<IrStmt>, name: String): IrLocal? =
        stmts.firstNotNullOfOrNull { findLocalInStmt(it, name) }

    private fun findLocalInStmt(stmt: IrStmt, name: String): IrLocal? = when (stmt) {
        is IrStmt.LocalAssign -> if (stmt.local.name == name) stmt.local else findLocalInExpr(stmt.value, name)
        is IrStmt.Await -> if (stmt.local.name == name) stmt.local else findLocalInExpr(stmt.target, name)
        is IrStmt.Call -> findLocalInExpr(stmt.receiver, name) ?: stmt.args.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrStmt.Eval -> findLocalInExpr(stmt.expr, name)
        is IrStmt.FieldAccess -> findLocalInExpr(stmt.receiver, name) ?: findLocalInExpr(stmt.value, name)
        is IrStmt.Branch -> findLocalInExpr(stmt.cond, name)
        is IrStmt.Return -> findLocalInExpr(stmt.value, name)
        is IrStmt.Throw -> findLocalInExpr(stmt.value, name)
        is IrStmt.Monitor -> findLocalInExpr(stmt.expr, name)
        is IrStmt.Try -> findLocalInList(stmt.body, name)
            ?: stmt.catches.firstNotNullOfOrNull { findLocalInList(it.body, name) }
            ?: stmt.finallyBody?.let { findLocalInList(it, name) }
        else -> null
    }

    private fun findLocalInExpr(e: IrExpr?, name: String): IrLocal? = when (e) {
        is IrExpr.LocalRead -> if (e.local.name == name) e.local else null
        is IrExpr.Invoke -> findLocalInExpr(e.receiver, name) ?: e.args.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrExpr.FnInvoke -> findLocalInExpr(e.fn, name) ?: e.args.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrExpr.Lambda -> e.captures.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrExpr.New -> e.args.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrExpr.ArrayLoad -> findLocalInExpr(e.base, name) ?: findLocalInExpr(e.index, name)
        is IrExpr.Arith -> findLocalInExpr(e.l, name) ?: findLocalInExpr(e.r, name)
        is IrExpr.Compare -> findLocalInExpr(e.l, name) ?: findLocalInExpr(e.r, name)
        is IrExpr.Not -> findLocalInExpr(e.operand, name)
        is IrExpr.Neg -> findLocalInExpr(e.operand, name)
        is IrExpr.Convert -> findLocalInExpr(e.expr, name)
        is IrExpr.IsType -> findLocalInExpr(e.expr, name)
        is IrExpr.StringTemplate -> e.parts.firstNotNullOfOrNull { findLocalInExpr(it, name) }
        is IrExpr.NullGuard -> findLocalInExpr(e.expr, name)
        is IrExpr.FieldRead -> findLocalInExpr(e.receiver, name)
        else -> null
    }

    private fun collectJumpTargets(stmts: List<IrStmt>): Set<IrLabel> {
        val targets = HashSet<IrLabel>()
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.Goto -> targets += stmt.label
                is IrStmt.Branch -> {
                    targets += stmt.then
                    targets += stmt.elseLabel
                }
                else -> Unit
            }
        }
        return targets
    }

    private fun containsAwait(stmt: IrStmt): Boolean = when (stmt) {
        is IrStmt.Await -> true
        is IrStmt.Try ->
            stmt.body.any { containsAwait(it) } ||
                stmt.catches.any { it.body.any { c -> containsAwait(c) } } ||
                (stmt.finallyBody?.any { containsAwait(it) } ?: false)
        else -> false
    }

    // ── 状态划分 ─────────────────────────────────────────────────────────────

    /**
     * 语句流划分：按跳转目标标签与挂起点切分为状态块，返回首状态索引。
     * [successor] 为流正常完成的续延状态；[context] 为流所处 try 区域。
     */
    private fun processFlow(g: MethodGen, stmts: List<IrStmt>, successor: Int, context: TryRegion?): Int {
        val entry = newState(context, isContinuation = false)
        var cur = entry
        val targets = collectJumpTargets(stmts)
        var i = 0
        while (i < stmts.size) {
            val stmt = stmts[i]
            when (stmt) {
                is IrStmt.Label -> {
                    if (stmt.label in targets) {
                        val targetState = newState(context, isContinuation = false)
                        labelToState[stmt.label] = targetState
                        states[cur].successor = targetState
                        states[targetState].code += stmt
                        cur = targetState
                    } else {
                        states[cur].code += stmt
                    }
                }
                is IrStmt.Await -> {
                    states[cur].code += IrStmt.Await(stmt.local, rewriteExpr(stmt.target), stmt.isSuspendCall, stmt.line)
                    val continuation = newState(context, isContinuation = true)
                    states[cur].suspendContinuation = continuation
                    states[continuation].resultBind = stmt.local
                    cur = continuation
                }
                is IrStmt.Try -> if (containsAwait(stmt)) {
                    val restState = if (i + 1 < stmts.size) {
                        processFlow(g, stmts.subList(i + 1, stmts.size), successor, context)
                    } else {
                        successor
                    }
                    states[cur].successor = expandTry(g, stmt, restState, context)
                    return entry
                } else {
                    states[cur].code += rewriteBodyStmt(stmt)
                }
                else -> states[cur].code += rewriteBodyStmt(stmt)
            }
            i++
        }
        states[cur].successor = successor
        return entry
    }

    /** 展开含挂起点的 try/catch/finally。返回 try 体首状态。 */
    private fun expandTry(g: MethodGen, stmt: IrStmt.Try, restState: Int, outer: TryRegion?): Int {
        val dispatch = newState(context = outer, isContinuation = false)
        val region = TryRegion(dispatch, outer, stmt.finallyBody)

        val hasFinally = stmt.finallyBody != null
        val normalCont = if (hasFinally) {
            processFlow(g, stmt.finallyBody!!, restState, outer)
        } else {
            restState
        }
        val bodyEntry = processFlow(g, stmt.body, normalCont, region)
        val catchEntries = stmt.catches.map { c -> processFlow(g, c.body, normalCont, outer) }

        // 填充 catch 分派
        g.withTarget(states[dispatch].code) {
            val e = g.newLocal("exc\$dispatch", lowering.throwableType)
            g.emit(IrStmt.LocalAssign(e, IrExpr.Convert(IrExpr.FieldRead(IrExpr.This, exceptionField), lowering.throwableType)))
            val noMatchL = g.newLabel()
            val catchStart = g.newLabel()
            // Kotlin 式自动重抛（T-M14）：CancellationException 不被 catch 吞掉，直接走 finally/重抛
            g.emit(
                IrStmt.Branch(
                    IrExpr.IsType(IrExpr.LocalRead(e), lowering.cancellationExceptionType),
                    noMatchL,
                    catchStart,
                ),
            )
            g.emit(IrStmt.Label(catchStart))
            stmt.catches.forEachIndexed { i, c ->
                val matchL = g.newLabel()
                val nextL = g.newLabel()
                val cond = if (c.type == null) {
                    IrExpr.Const(true)
                } else {
                    IrExpr.IsType(IrExpr.LocalRead(e), c.type)
                }
                g.emit(IrStmt.Branch(cond, matchL, nextL))
                g.emit(IrStmt.Label(matchL))
                g.emit(IrStmt.FieldAccess(IrExpr.This, exceptionField, write = true, value = IrExpr.Const(null)))
                val paramField = localFields.entries.firstOrNull { it.key.name == c.paramName }?.value
                if (paramField != null) {
                    g.emit(IrStmt.FieldAccess(IrExpr.This, paramField, write = true, value = adaptedTo(paramField.type, IrExpr.LocalRead(e))))
                }
                g.emit(IrStmt.Goto(stateLabel(catchEntries[i])))
                g.emit(IrStmt.Label(nextL))
            }
            g.emit(IrStmt.Label(noMatchL))
            if (hasFinally) {
                g.emit(IrStmt.FieldAccess(IrExpr.This, rethrownField, write = true, value = IrExpr.LocalRead(e)))
                g.emit(IrStmt.Goto(stateLabel(excState(g, region))))
            } else {
                g.emit(IrStmt.Throw(IrExpr.LocalRead(e)))
            }
        }
        return bodyEntry
    }

    /** 异常传播路径：外层有 finally 则先跑 finally 再继续向外，否则直接重抛。 */
    private fun excState(g: MethodGen, region: TryRegion): Int {
        if (region.excStateCache >= 0) return region.excStateCache
        val target = if (region.finallyBody != null) {
            val rethrowState = newState(region.outer, isContinuation = false)
            states[rethrowState].code += IrStmt.Throw(
                IrExpr.Convert(IrExpr.FieldRead(IrExpr.This, rethrownField), lowering.throwableType),
            )
            processFlow(g, region.finallyBody, rethrowState, region.outer)
        } else if (region.outer != null) {
            excState(g, region.outer)
        } else {
            val throwState = newState(null, isContinuation = false)
            states[throwState].code += IrStmt.Throw(
                IrExpr.Convert(IrExpr.FieldRead(IrExpr.This, rethrownField), lowering.throwableType),
            )
            throwState
        }
        region.excStateCache = target
        return target
    }

    /** 返回路径：外层有 finally 则先跑 finally 再继续向外，否则直接返回。 */
    private fun returnState(g: MethodGen, region: TryRegion): Int {
        if (region.returnStateCache >= 0) return region.returnStateCache
        val target = if (region.finallyBody != null) {
            processFlow(
                g,
                region.finallyBody,
                if (region.outer != null) returnState(g, region.outer) else actualReturnState,
                region.outer,
            )
        } else if (region.outer != null) {
            returnState(g, region.outer)
        } else {
            actualReturnState
        }
        region.returnStateCache = target
        return target
    }

    // ── 表达式/语句重写（局部 → 字段）────────────────────────────────────────

    private fun rewriteExpr(e: IrExpr): IrExpr = when (e) {
        is IrExpr.LocalRead -> IrExpr.FieldRead(IrExpr.This, fieldOf(e.local))
        is IrExpr.This -> IrExpr.FieldRead(IrExpr.This, receiverField)
        is IrExpr.FieldRead -> IrExpr.FieldRead(e.receiver?.let { rewriteExpr(it) }, e.field)
        is IrExpr.New -> IrExpr.New(e.type, e.args.map { rewriteExpr(it) })
        is IrExpr.ArrayLoad -> IrExpr.ArrayLoad(rewriteExpr(e.base), rewriteExpr(e.index), e.elemType)
        is IrExpr.Arith -> IrExpr.Arith(e.op, rewriteExpr(e.l), rewriteExpr(e.r))
        is IrExpr.Compare -> IrExpr.Compare(e.op, rewriteExpr(e.l), rewriteExpr(e.r))
        is IrExpr.Not -> IrExpr.Not(rewriteExpr(e.operand))
        is IrExpr.Neg -> IrExpr.Neg(rewriteExpr(e.operand))
        is IrExpr.Convert -> IrExpr.Convert(rewriteExpr(e.expr), e.to)
        is IrExpr.IsType -> IrExpr.IsType(rewriteExpr(e.expr), e.type)
        is IrExpr.Invoke -> IrExpr.Invoke(e.target, e.receiver?.let { rewriteExpr(it) }, e.args.map { rewriteExpr(it) }, e.isSuper)
        is IrExpr.FnInvoke -> IrExpr.FnInvoke(rewriteExpr(e.fn), e.args.map { rewriteExpr(it) })
        is IrExpr.StringTemplate -> IrExpr.StringTemplate(e.parts.map { rewriteExpr(it) })
        is IrExpr.NullGuard -> IrExpr.NullGuard(rewriteExpr(e.expr))
        is IrExpr.Lambda -> rewriteLambda(e)
        else -> e
    }

    /** 实例方法实现的 Lambda 在驱动中 `this` 为状态机 → 生成静态桥接（receiver 前置为捕获）。 */
    private val lambdaBridges = mutableMapOf<IrMethodRef, IrMethodRef>()
    private var bridgeCounter = 0

    private fun rewriteLambda(lambda: IrExpr.Lambda): IrExpr {
        val impl = lambda.target.method
        if (impl.isStatic || impl.owner == null) {
            return IrExpr.Lambda(lambda.target, lambda.captures.map { rewriteExpr(it) })
        }
        val bridge = lambdaBridges.getOrPut(lambda.target) { createLambdaBridge(impl) }
        return IrExpr.Lambda(
            bridge,
            listOf(IrExpr.FieldRead(IrExpr.This, receiverField)) + lambda.captures.map { rewriteExpr(it) },
        )
    }

    /** 静态桥接：`bridge(receiver, <impl.params>...)` 调用原实例 Lambda 实现。 */
    private fun createLambdaBridge(impl: IrMethod): IrMethodRef {
        val name = "lambdaBridge\$${bridgeCounter++}"
        val params = listOf(IrParam("receiver", IrType.ANY)) +
            impl.params.map { IrParam(it.name, it.type) }
        val bridge = IrMethod(
            name = name,
            params = params,
            returnType = impl.returnType,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = smClass,
        )
        val receiverType = impl.owner?.let { IrType.Declared(yxClassSymbol(it.name), emptyList()) }
        val g = MethodGen(bridge, smClass)
        g.emit(
            IrStmt.Return(
                IrExpr.Invoke(
                    IrMethodRef(impl),
                    receiverType?.let { IrExpr.Convert(IrExpr.LocalRead(g.paramLocals[0]), it) } ?: IrExpr.LocalRead(g.paramLocals[0]),
                    (1..params.lastIndex).map { IrExpr.LocalRead(g.paramLocals[it]) },
                ),
            ),
        )
        smClass.methods.add(bridge)
        return IrMethodRef(bridge)
    }

    /** 生成类符号（后端 module.classNamed 解析构造器/内部名）。 */
    private fun yxClassSymbol(name: String): YxClassSymbol = YxClassSymbol(
        name = name,
        typeParams = emptyList(),
        isData = false,
        isService = false,
        superType = null,
        interfaces = emptyList(),
        members = mutableListOf(),
        isAbstract = false,
        isSealed = false,
        annotations = emptyList(),
        visibility = YxVisibility.PUBLIC,
        fileScope = null,
        span = null,
    )

    /** 适配字段存储：值类型与字段类型不一致时插 Convert（FieldAccess 不做装箱/拆箱适配）。 */
    private fun adaptedTo(fieldType: IrType, value: IrExpr): IrExpr {
        val vt = value.inferType() ?: return value
        return if (vt.equivalent(fieldType)) value else IrExpr.Convert(value, fieldType)
    }

    private fun fieldOf(local: IrLocal): IrField =
        localFields.getOrPut(local) {
            val name = "l${local.index}\$${local.name}"
            IrField(name, local.type, isStatic = false, isFinal = false)
        }

    /** 结构化语句重写（Try 内部：局部→字段，控制流原样保留）。 */
    private fun rewriteStructured(stmt: IrStmt): IrStmt = when (stmt) {
        is IrStmt.LocalAssign -> IrStmt.FieldAccess(IrExpr.This, fieldOf(stmt.local), write = true, value = adaptedTo(stmt.local.type, rewriteExpr(stmt.value)), stmt.line)
        is IrStmt.Call -> IrStmt.Call(stmt.callee, stmt.receiver?.let { rewriteExpr(it) }, stmt.args.map { rewriteExpr(it) }, stmt.ret, stmt.isSuper, stmt.line)
        is IrStmt.New -> IrStmt.New(stmt.type, stmt.args.map { rewriteExpr(it) }, stmt.line)
        is IrStmt.Eval -> IrStmt.Eval(rewriteExpr(stmt.expr), stmt.line)
        is IrStmt.FieldAccess -> IrStmt.FieldAccess(stmt.receiver?.let { rewriteExpr(it) }, stmt.field, stmt.write, stmt.value?.let { adaptedTo(stmt.field.type, rewriteExpr(it)) }, stmt.line)
        is IrStmt.Branch -> IrStmt.Branch(rewriteExpr(stmt.cond), stmt.then, stmt.elseLabel, stmt.line)
        is IrStmt.Return -> IrStmt.Return(stmt.value?.let { rewriteExpr(it) }, stmt.line)
        is IrStmt.Throw -> IrStmt.Throw(rewriteExpr(stmt.value), stmt.line)
        is IrStmt.Monitor -> IrStmt.Monitor(rewriteExpr(stmt.expr), stmt.enter, stmt.line)
        is IrStmt.Try -> IrStmt.Try(
            stmt.body.map { rewriteStructured(it) },
            stmt.catches.map { IrCatch(it.paramName, it.type, it.body.map { b -> rewriteStructured(b) }) },
            stmt.finallyBody?.map { rewriteStructured(it) },
            stmt.line,
        )
        else -> stmt
    }

    /**
     * 原方法体语句 → 状态机形式（分区时应用一次）：局部→字段、`this`→receiver 字段；
     * Goto/Branch 保留标签（生成时解析为状态跳转）。
     */
    private fun rewriteBodyStmt(stmt: IrStmt): IrStmt = when (stmt) {
        is IrStmt.Await -> IrStmt.Await(stmt.local, rewriteExpr(stmt.target), stmt.isSuspendCall, stmt.line)
        is IrStmt.Try -> rewriteStructured(stmt)
        is IrStmt.Branch -> IrStmt.Branch(rewriteExpr(stmt.cond), stmt.then, stmt.elseLabel, stmt.line)
        is IrStmt.Return -> IrStmt.Return(stmt.value?.let { rewriteExpr(it) }, stmt.line)
        else -> rewriteStructured(stmt)
    }

    // ── 驱动（invokeSuspend）────────────────────────────────────────────────

    private fun driverMethod(): IrMethod = IrMethod(
        name = "invokeSuspend",
        params = emptyList(),
        returnType = IrType.ANY,
        isStatic = false,
        isConstructor = false,
        isAsync = false,
        isOverride = false,
        isSynthetic = true,
        visibility = YxVisibility.PUBLIC,
        owner = smClass,
    )

    private fun invokeSuspendOn(sm: IrExpr): IrExpr.Invoke =
        IrExpr.Invoke(
            IrJvmCall("invokeSuspend", smName, static = false, params = emptyList(), retType = IrType.ANY),
            sm,
            emptyList(),
        )

    private fun genStateCode(g: MethodGen, s: State) {
        g.emit(IrStmt.Label(stateLabel(s.index)))
        val block = mutableListOf<IrStmt>()
        g.withTarget(block) {
            if (s.isContinuation) {
                emitExceptionCheck(g, s)
                s.resultBind?.let { local ->
                    g.emit(IrStmt.FieldAccess(IrExpr.This, fieldOf(local), write = true, value = adaptedTo(local.type, IrExpr.FieldRead(IrExpr.This, resultField))))
                }
            }
            for (stmt in s.code) {
                g.emit(genStateStmt(g, s, stmt))
            }
            if (s.suspendContinuation < 0 && !endsWithTerminator(block)) {
                if (s.successor >= 0) g.emit(IrStmt.Goto(stateLabel(successorOf(s))))
            }
        }
        val emitted = s.context?.let { wrapRoutingTry(block, it, g) } ?: block
        emitted.forEach { g.emit(it) }
    }

    private fun endsWithTerminator(block: List<IrStmt>): Boolean {
        val last = block.lastOrNull() ?: return false
        return last is IrStmt.Goto || last is IrStmt.Branch || last is IrStmt.Return || last is IrStmt.Throw
    }

    /** 续延状态入口：挂起恢复带异常时路由到 try 区域分派（无区域则重抛）。 */
    private fun emitExceptionCheck(g: MethodGen, s: State) {
        val continueL = g.newLabel()
        val routeL = g.newLabel()
        val isNull = IrExpr.Compare(CompareOp.EQ, IrExpr.FieldRead(IrExpr.This, exceptionField), IrExpr.Const(null))
        g.emit(IrStmt.Branch(isNull, continueL, routeL))
        g.emit(IrStmt.Label(routeL))
        val target = s.context?.dispatchState
        if (target != null) {
            g.emit(IrStmt.Goto(stateLabel(target)))
        } else {
            g.emit(
                IrStmt.Throw(
                    IrExpr.Convert(IrExpr.FieldRead(IrExpr.This, exceptionField), lowering.throwableType),
                ),
            )
        }
        g.emit(IrStmt.Label(continueL))
    }

    /** 状态块外包结构化 try：块内异常写入 exception 字段并路由到所在区域分派。 */
    private fun wrapRoutingTry(block: MutableList<IrStmt>, context: TryRegion, g: MethodGen): List<IrStmt> {
        val catchLocal = g.newLocal("exc\$routing", lowering.throwableType)
        val catchBody = mutableListOf<IrStmt>()
        g.withTarget(catchBody) {
            g.emit(IrStmt.FieldAccess(IrExpr.This, exceptionField, write = true, value = IrExpr.LocalRead(catchLocal)))
            g.emit(IrStmt.Goto(stateLabel(context.dispatchState)))
        }
        return listOf(IrStmt.Try(block, listOf(IrCatch("exc\$routing", null, catchBody)), null))
    }

    private fun genStateStmt(g: MethodGen, s: State, stmt: IrStmt): IrStmt = when (stmt) {
        is IrStmt.Await -> {
            genAwait(g, s, stmt)
            IrStmt.Nop
        }
        is IrStmt.Goto -> labelToState[stmt.label]?.let { IrStmt.Goto(stateLabel(it)) } ?: stmt
        is IrStmt.Branch -> {
            val t = labelToState[stmt.then]
            val e = labelToState[stmt.elseLabel]
            if (t != null && e != null) IrStmt.Branch(stmt.cond, stateLabel(t), stateLabel(e)) else stmt
        }
        is IrStmt.Return -> genReturn(g, s, stmt)
        else -> stmt // 其余已在分区时经 rewriteBodyStmt 重写，原样通过
    }

    /** `return`：try 区域内先跑 finally（returnState 链）再返回；否则直接返回。 */
    private fun genReturn(g: MethodGen, s: State, stmt: IrStmt.Return): IrStmt {
        val value = stmt.value // 已在分区时重写
        val context = s.context
        if (context == null) return stmt
        // 置返回值 → 进入返回路径（跑完所有外层 finally 后返回）
        val entry = newState(context, isContinuation = false)
        g.withTarget(states[entry].code) {
            g.emit(IrStmt.FieldAccess(IrExpr.This, returnValueField, write = true, value = value?.let { adaptedTo(IrType.ANY, it) } ?: IrExpr.Const(null)))
            g.emit(IrStmt.Goto(stateLabel(returnState(g, context))))
        }
        return IrStmt.Goto(stateLabel(entry))
    }

    /** 挂起点：置下一状态 → 挂起调用/tryAwait → 挂起（返回 SUSPENDED）或继续。 */
    private fun genAwait(g: MethodGen, s: State, stmt: IrStmt.Await) {
        val continuation = s.suspendContinuation
        require(continuation >= 0) { "CPS: await 状态缺少续延状态: ${s.index}" }
        g.emit(IrStmt.FieldAccess(IrExpr.This, stateField, write = true, value = IrExpr.Const(continuation)))
        val v = g.newLocal("await\$v", IrType.ANY)
        if (stmt.isSuspendCall) {
            // 挂起入口调用（补 Continuation=this）；同步完成直接得值，挂起返回 SUSPENDED
            val invoke = stmt.target as? IrExpr.Invoke
                ?: error("CPS: isSuspendCall 目标的 Invoke 缺失: ${stmt.target}")
            val argsWithCont = invoke.args + IrExpr.This
            g.emit(IrStmt.LocalAssign(v, IrExpr.Invoke(invoke.target, invoke.receiver, argsWithCont, invoke.isSuper)))
            emitSuspendedCheck(g, v)
            g.emit(IrStmt.FieldAccess(IrExpr.This, resultField, write = true, value = IrExpr.LocalRead(v)))
        } else {
            // Task/CompletableFuture → Continuations.tryAwait(目标, this)
            val target = stmt.target
            g.emit(IrStmt.LocalAssign(v, lowering.tryAwaitCall(target, IrExpr.This)))
            emitSuspendedCheck(g, v)
            // PendingException → 置 exception，交由续延状态入口路由
            val notPendingL = g.newLabel()
            val pendingL = g.newLabel()
            g.emit(IrStmt.Branch(IrExpr.IsType(IrExpr.LocalRead(v), lowering.pendingExceptionType), pendingL, notPendingL))
            g.emit(IrStmt.Label(pendingL))
            g.emit(
                IrStmt.FieldAccess(
                    IrExpr.This,
                    exceptionField,
                    write = true,
                    value = IrExpr.FieldRead(
                        IrExpr.Convert(IrExpr.LocalRead(v), lowering.pendingExceptionType),
                        IrField("throwable", IrType.ANY, isStatic = false, isFinal = false, owner = "yux.async.Continuations\$PendingException"),
                    ),
                ),
            )
            g.emit(IrStmt.Goto(stateLabel(continuation)))
            g.emit(IrStmt.Label(notPendingL))
            g.emit(IrStmt.FieldAccess(IrExpr.This, resultField, write = true, value = IrExpr.LocalRead(v)))
        }
        g.emit(IrStmt.Goto(stateLabel(continuation)))
    }

    /** `if (v === SUSPENDED) return SUSPENDED`。 */
    private fun emitSuspendedCheck(g: MethodGen, v: IrLocal) {
        val notSuspendedL = g.newLabel()
        val suspendedL = g.newLabel()
        g.emit(
            IrStmt.Branch(
                IrExpr.Compare(CompareOp.EQ, IrExpr.LocalRead(v), lowering.suspendedRead),
                suspendedL,
                notSuspendedL,
            ),
        )
        g.emit(IrStmt.Label(suspendedL))
        g.emit(IrStmt.Return(lowering.suspendedRead))
        g.emit(IrStmt.Label(notSuspendedL))
    }

    // ── 组装 ────────────────────────────────────────────────────────────────

    fun generate() {
        collectLocalRefs().forEach { fieldOf(it) }
        smClass.fields += listOf(stateField, receiverField, completionField, resultField, exceptionField, rethrownField, returnValueField)
        smClass.fields += localFields.values

        val driver = driverMethod()
        smClass.methods += driver
        val g = MethodGen(driver, smClass)
        // 状态划分（入口 = 状态 0；顶层流正常结束 → ACTUAL_RETURN 哨兵，其后创建实际返回状态）
        val entry = processFlow(g, facade.body, ACTUAL_RETURN, null)
        actualReturnState = newState(null, isContinuation = false)
        states[actualReturnState].code += IrStmt.Return(IrExpr.FieldRead(IrExpr.This, returnValueField))
        genDispatch(g)
        // 代码生成可能新增状态（returnState/excState 的 finally 流），索引循环直到收敛
        var i = 0
        while (i < states.size) {
            genStateCode(g, states[i])
            i++
        }

        smClass.methods += genResume()
        smClass.methods += genResumeWithException()
        smClass.methods += genConstructor()

        module.classes.add(smClass)

        facade.body.clear()
        genFacadeBody()
        suspendEntry.body.clear()
        genSuspendEntryBody()
    }

    private fun genDispatch(g: MethodGen) {
        for (k in 0..states.lastIndex) {
            val nextCheck = g.newLabel()
            val eq = IrExpr.Compare(CompareOp.EQ, IrExpr.FieldRead(IrExpr.This, stateField), IrExpr.Const(k))
            g.emit(IrStmt.Branch(eq, stateLabel(k), nextCheck))
            g.emit(IrStmt.Label(nextCheck))
        }
        g.emit(IrStmt.Return(IrExpr.Const(null)))
    }

    private fun genConstructor(): IrMethod {
        val receiverParam = IrParam("receiver", IrType.ANY)
        val paramIrs = facade.params.map { IrParam(it.name, it.type) }
        val completionParam = IrParam("completion", lowering.continuationType)
        val ctor = IrMethod(
            name = "<init>",
            params = listOf(receiverParam) + paramIrs + completionParam,
            returnType = IrType.Void,
            isStatic = false,
            isConstructor = true,
            isAsync = false,
            isOverride = false,
            isSynthetic = true,
            owner = smClass,
        )
        val g = MethodGen(ctor, smClass)
        g.emit(IrStmt.FieldAccess(IrExpr.This, stateField, write = true, value = IrExpr.Const(0)))
        g.emit(IrStmt.FieldAccess(IrExpr.This, receiverField, write = true, value = IrExpr.LocalRead(g.paramLocals[0])))
        paramIrs.forEachIndexed { i, _ ->
            val field = fieldOf(facade.paramLocals[i])
            g.emit(IrStmt.FieldAccess(IrExpr.This, field, write = true, value = IrExpr.LocalRead(g.paramLocals[i + 1])))
        }
        g.emit(IrStmt.FieldAccess(IrExpr.This, completionField, write = true, value = IrExpr.LocalRead(g.paramLocals[paramIrs.size + 1])))
        return ctor
    }

    /** `resume`/`resumeWithException`：写 result/exception → 驱动 → 未挂起则转发 completion。 */
    private fun genResume(): IrMethod = genContinuationMethod(
        name = "resume",
        param = IrParam("value", IrType.ANY),
        field = resultField,
        sourceLocal = "value",
    )

    private fun genResumeWithException(): IrMethod = genContinuationMethod(
        name = "resumeWithException",
        param = IrParam("t", lowering.throwableType),
        field = exceptionField,
        sourceLocal = "t",
    )

    private fun genContinuationMethod(name: String, param: IrParam, field: IrField, sourceLocal: String): IrMethod {
        val m = IrMethod(
            name = name,
            params = listOf(param),
            returnType = IrType.Void,
            isStatic = false,
            isConstructor = false,
            isAsync = false,
            isOverride = true,
            isSynthetic = true,
            owner = smClass,
        )
        val g = MethodGen(m, smClass)
        g.emit(IrStmt.FieldAccess(IrExpr.This, field, write = true, value = IrExpr.LocalRead(g.lookupLocal(sourceLocal)!!)))
        val driveBody = mutableListOf<IrStmt>()
        g.withTarget(driveBody) {
            val r = g.newLocal("drive\$r", IrType.ANY)
            g.emit(IrStmt.LocalAssign(r, invokeSuspendOn(IrExpr.This)))
            val skipL = g.newLabel()
            val fwdL = g.newLabel()
            g.emit(IrStmt.Branch(IrExpr.Compare(CompareOp.EQ, IrExpr.LocalRead(r), lowering.suspendedRead), skipL, fwdL))
            g.emit(IrStmt.Label(fwdL))
            g.emit(lowering.completionResumeCall(IrExpr.FieldRead(IrExpr.This, completionField), IrExpr.LocalRead(r)))
            g.emit(IrStmt.Label(skipL))
        }
        val catchLocal = g.newLocal("drive\$e", lowering.throwableType)
        val catchBody = mutableListOf<IrStmt>()
        g.withTarget(catchBody) {
            g.emit(lowering.completionResumeWithExceptionCall(IrExpr.FieldRead(IrExpr.This, completionField), IrExpr.LocalRead(catchLocal)))
        }
        g.emit(IrStmt.Try(driveBody, listOf(IrCatch("drive\$e", null, catchBody)), null))
        return m
    }

    /** 同步门面：构造状态机 + FutureCompletion，急切执行到首挂起点，结果写入 future。
     *  async{} 块门面（isLaunchedAsync）改为池上驱动（Continuations.launch），不阻塞调用线程。 */
    private fun genFacadeBody() {
        val g = MethodGen(facade, owner)
        val sm = g.newLocal("sm", smType)
        val receiver: IrExpr = if (facade.isStatic) IrExpr.Const(null) else IrExpr.This
        if (facade.isLaunchedAsync) {
            // async{} 块：构造状态机 + FutureCompletion，经 Continuations.launch 池上驱动，立即返回 Task
            val future = g.newLocal("future", lowering.completableFutureType)
            val completion = g.newLocal("completion", lowering.futureCompletionType)
            g.emit(IrStmt.LocalAssign(future, lowering.newFutureCall()))
            g.emit(IrStmt.LocalAssign(completion, lowering.newFutureCompletion(IrExpr.LocalRead(future))))
            val args: List<IrExpr> = facade.paramLocals.map { IrExpr.LocalRead(it) as IrExpr }
            g.emit(IrStmt.LocalAssign(sm, IrExpr.New(smType, listOf(receiver) + args + listOf(IrExpr.LocalRead(completion)))))
            g.emit(
                IrStmt.Return(
                    IrExpr.Invoke(
                        IrJvmCall("launch", "yux.async.Continuations", static = true, params = listOf(lowering.suspendableType, lowering.futureCompletionType), retType = lowering.taskType),
                        null,
                        listOf(IrExpr.LocalRead(sm), IrExpr.LocalRead(completion)),
                    ),
                ),
            )
            return
        }
        val future = g.newLocal("future", lowering.completableFutureType)
        g.emit(IrStmt.LocalAssign(future, lowering.newFutureCall()))
        val args: List<IrExpr> = facade.paramLocals.map { IrExpr.LocalRead(it) as IrExpr }
        g.emit(IrStmt.LocalAssign(sm, IrExpr.New(smType, listOf(receiver) + args + listOf(lowering.newFutureCompletion(IrExpr.LocalRead(future))))))
        val driveBody = mutableListOf<IrStmt>()
        g.withTarget(driveBody) {
            val r = g.newLocal("facade\$r", IrType.ANY)
            g.emit(IrStmt.LocalAssign(r, invokeSuspendOn(IrExpr.LocalRead(sm))))
            val skipL = g.newLabel()
            val fwdL = g.newLabel()
            g.emit(IrStmt.Branch(IrExpr.Compare(CompareOp.EQ, IrExpr.LocalRead(r), lowering.suspendedRead), skipL, fwdL))
            g.emit(IrStmt.Label(fwdL))
            g.emit(lowering.futureCompleteCall(IrExpr.LocalRead(future), IrExpr.LocalRead(r)))
            g.emit(IrStmt.Label(skipL))
        }
        val catchLocal = g.newLocal("facade\$e", lowering.throwableType)
        val catchBody = mutableListOf<IrStmt>()
        g.withTarget(catchBody) {
            g.emit(lowering.futureCompleteExceptionallyCall(IrExpr.LocalRead(future), IrExpr.LocalRead(catchLocal)))
        }
        g.emit(IrStmt.Try(driveBody, listOf(IrCatch("facade\$e", null, catchBody)), null))
        g.emit(IrStmt.Return(lowering.wrapCall(IrExpr.LocalRead(future))))
    }

    /** 挂起入口：构造状态机（completion=调用方续延），同步执行到首挂起点。 */
    private fun genSuspendEntryBody() {
        val g = MethodGen(suspendEntry, owner)
        val sm = g.newLocal("sm", smType)
        val declaredParams = suspendEntry.params.dropLast(1)
        val receiver: IrExpr = if (suspendEntry.isStatic) IrExpr.Const(null) else IrExpr.This
        val completionParam = suspendEntry.paramLocals.last()
        val args: List<IrExpr> = declaredParams.indices.map { IrExpr.LocalRead(suspendEntry.paramLocals[it]) as IrExpr }
        g.emit(IrStmt.LocalAssign(sm, IrExpr.New(smType, listOf(receiver) + args + listOf(IrExpr.LocalRead(completionParam)))))
        g.emit(IrStmt.Return(invokeSuspendOn(IrExpr.LocalRead(sm))))
    }
}
