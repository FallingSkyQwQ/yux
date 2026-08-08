package yux.compiler.irgen

import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAsyncBlock
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBreak
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxContinue
import yux.compiler.ast.YxElseCondition
import yux.compiler.ast.YxExprCondition
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxFor
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIf
import yux.compiler.ast.YxIsCondition
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxParallelBlock
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxStmt
import yux.compiler.ast.YxThrow
import yux.compiler.ast.YxTry
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxTypeReference
import yux.compiler.ast.YxUnsafeBlock
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhile
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCatch
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.FileScope
import yux.compiler.sema.FunctionSymbol
import yux.compiler.sema.PropertySymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.VariableSymbol
import yux.compiler.sema.YxClassSymbol

/**
 * 语句下沉（T-M4-4，02-§8.2）：if→Branch/Goto、while→Branch、for→迭代器展开、
 * when→分支链、`&&`/`||` 短路、try→结构化 Try。
 *
 * 所有发射经 [MethodGen.emit] 落盘到**当前发射目标**（默认方法体；Try/catch/finally
 * 子块经 [MethodGen.withTarget] 切到子列表），保证短路/表达式赋值等表达式级发射
 * 也落在正确的语句列表内（否则 try 体内条件表达式会被提升到方法体顶层，破坏异常语义）。
 */
class StmtGen(
    private val irGen: IRGen,
) {
    // 无内部状态：单实例复用（gen 递归中高频访问）
    private val exprGen: ExprGen = ExprGen(irGen, irGen.resolver)

    fun gen(stmt: YxStmt, g: MethodGen, fileScope: FileScope) {
        when (stmt) {
            is YxBlock -> {
                g.enterScope()
                stmt.statements.forEach { gen(it, g, fileScope) }
                g.exitScope()
            }
            is YxVarDecl -> genVarDecl(stmt, g, fileScope)
            is YxAssign -> genAssign(stmt, g, fileScope)
            is YxExprStmt -> genExprStmt(stmt, g, fileScope)
            is YxIf -> genIf(stmt, g, fileScope)
            is YxWhile -> genWhile(stmt, g, fileScope)
            is YxFor -> genFor(stmt, g, fileScope)
            is YxWhen -> genWhen(stmt, g, fileScope)
            is YxReturn -> g.emit(IrStmt.Return(stmt.value?.let { exprGen.gen(it, g, fileScope) }))
            is YxBreak -> g.currentLoop?.let { g.emit(IrStmt.Goto(it.second)) }
            is YxContinue -> g.currentLoop?.let { g.emit(IrStmt.Goto(it.first)) }
            is YxTry -> genTry(stmt, g, fileScope)
            // M4 同步降级（R3）：async/parallel/unsafe 块体直接内联，语义后置
            is YxAsyncBlock, is YxParallelBlock, is YxUnsafeBlock -> {
                val block = when (stmt) {
                    is YxAsyncBlock -> stmt.body
                    is YxParallelBlock -> stmt.body
                    else -> (stmt as YxUnsafeBlock).body
                }
                g.enterScope()
                block.statements.forEach { gen(it, g, fileScope) }
                g.exitScope()
            }
            else -> Unit
        }
    }

    private fun genVarDecl(stmt: YxVarDecl, g: MethodGen, fileScope: FileScope) {
        val init = stmt.initializer
        if (init == null) {
            // 无初始化器：先占位注册（确定性赋值由 S-6.1.2 保证使用前已赋值）
            val type = stmt.type?.let { irGen.resolveIrType(it, fileScope) } ?: IrType.Error
            g.declareLocal(stmt.name, g.newLocal(stmt.name, type))
            return
        }
        val type = TypeBridge.toIr(irGen.exprTypes[init] ?: SemaType.ErrorT)
        val local = g.newLocal(stmt.name, type)
        g.declareLocal(stmt.name, local)
        g.emit(IrStmt.LocalAssign(local, exprGen.gen(init, g, fileScope)))
    }

    private fun genAssign(stmt: YxAssign, g: MethodGen, fileScope: FileScope) {
        val op = compoundOp(stmt.op)
        when (val t = stmt.target) {
            is YxIdentifier -> when (val sym = irGen.resolvedRefs[t]) {
                is VariableSymbol -> {
                    val local = g.lookupLocal(sym.name)
                        ?: error("IRGen: 赋值目标未登记: ${sym.name}")
                    val value = if (op == null) {
                        exprGen.gen(stmt.value, g, fileScope)
                    } else {
                        IrExpr.Arith(op, IrExpr.LocalRead(local), exprGen.gen(stmt.value, g, fileScope))
                    }
                    g.emit(IrStmt.LocalAssign(local, value))
                }
                is PropertySymbol -> {
                    val setter = irGen.propertyAccessors[sym]?.setter
                        ?: error("IRGen: 属性 setter 未登记: ${sym.name}")
                    val getter = irGen.propertyAccessors[sym]?.getter
                        ?: error("IRGen: 属性 getter 未登记: ${sym.name}")
                    val value = if (op == null) {
                        exprGen.gen(stmt.value, g, fileScope)
                    } else {
                        // 复合赋值读取侧走 getter（S-5.2 自定义访问器语义）
                        IrExpr.Arith(op, IrExpr.Invoke(IrMethodRef(getter), IrExpr.This, emptyList()), exprGen.gen(stmt.value, g, fileScope))
                    }
                    g.emit(IrStmt.Call(IrMethodRef(setter), IrExpr.This, listOf(value), IrType.Void))
                }
                else -> error("IRGen: 不支持的赋值目标: ${t.name}")
            }
            is YxMemberAccess -> genMemberAssign(stmt, t, op, g, fileScope)
            else -> error("IRGen: 不支持的赋值目标: ${t::class.simpleName}")
        }
    }

    private fun genMemberAssign(
        stmt: YxAssign,
        target: YxMemberAccess,
        op: ArithOp?,
        g: MethodGen,
        fileScope: FileScope,
    ) {
        val receiverType = irGen.exprTypes[target.receiver]
        val receiver = if (target.receiver is YxTypeReference) null else exprGen.gen(target.receiver, g, fileScope)
        val rt = SemaType.resolveVar(receiverType ?: SemaType.ErrorT)
        when {
            rt is SemaType.Declared && rt.symbol is YxClassSymbol -> {
                val prop = (rt.symbol as YxClassSymbol).property(target.name)
                    ?: error("IRGen: 赋值目标属性不存在: ${target.name}")
                val accessor = irGen.propertyAccessors[prop]
                    ?: error("IRGen: 属性访问器未登记: ${target.name}")
                val setter = accessor.setter ?: error("IRGen: 属性 setter 不存在: ${target.name}")
                if (op == null) {
                    g.emit(IrStmt.Call(IrMethodRef(setter), receiver, listOf(exprGen.gen(stmt.value, g, fileScope)), IrType.Void))
                    return
                }
                val getter = accessor.getter ?: error("IRGen: 属性 getter 不存在: ${target.name}")
                // 复合赋值：接收者求值一次（副作用安全），读取走 getter；静态访问无复合赋值
                val instanceReceiver = receiver ?: error("IRGen: 复合赋值需要实例接收者: ${target.name}")
                val receiverLocal = g.newLocal("receiver", TypeBridge.toIr(rt))
                g.emit(IrStmt.LocalAssign(receiverLocal, instanceReceiver))
                val value = IrExpr.Arith(
                    op,
                    IrExpr.Invoke(IrMethodRef(getter), IrExpr.LocalRead(receiverLocal), emptyList()),
                    exprGen.gen(stmt.value, g, fileScope),
                )
                g.emit(IrStmt.Call(IrMethodRef(setter), IrExpr.LocalRead(receiverLocal), listOf(value), IrType.Void))
            }
            else -> {
                // JVM JavaBean setter：`p.name = x` → `p.setName(x)`
                val setter = irGen.resolver.resolveInstanceSetter(rt, target.name)
                    ?: error("IRGen: JVM setter 不存在: ${target.name} on ${rt.render()}")
                if (op == null) {
                    g.emit(IrStmt.Call(setter, receiver, listOf(exprGen.gen(stmt.value, g, fileScope)), IrType.Void))
                    return
                }
                // 复合赋值：读取 getter + op + 写 setter（op 不得静默丢失）
                val getter = irGen.resolver.resolveInstanceMethod(rt, target.name)
                    ?: error("IRGen: JVM getter 不存在: ${target.name} on ${rt.render()}")
                val instanceReceiver = receiver ?: error("IRGen: 复合赋值需要实例接收者: ${target.name}")
                val receiverLocal = g.newLocal("receiver", TypeBridge.toIr(rt))
                g.emit(IrStmt.LocalAssign(receiverLocal, instanceReceiver))
                val value = IrExpr.Arith(op, IrExpr.Invoke(getter, IrExpr.LocalRead(receiverLocal), emptyList()), exprGen.gen(stmt.value, g, fileScope))
                g.emit(IrStmt.Call(setter, IrExpr.LocalRead(receiverLocal), listOf(value), IrType.Void))
            }
        }
    }

    private fun genExprStmt(stmt: YxExprStmt, g: MethodGen, fileScope: FileScope) {
        when (val expr = stmt.expr) {
            is YxCall -> emitCallStatement(expr, g, fileScope)
            is YxAssign -> genAssign(expr, g, fileScope)
            is YxThrow -> g.emit(IrStmt.Throw(exprGen.gen(expr.expr, g, fileScope)))
            is YxTypeCall -> g.emit(
                IrStmt.New(
                    TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ErrorT),
                    expr.args.map { exprGen.gen(it, g, fileScope) },
                ),
            )
            else -> Unit // 纯值语句无副作用（M4 子集）
        }
    }

    /** 语句位置调用（结果丢弃）。 */
    private fun emitCallStatement(call: YxCall, g: MethodGen, fileScope: FileScope) {
        val args = call.args.map { exprGen.gen(it, g, fileScope) }
        when (val callee = call.callee) {
            is YxIdentifier -> {
                val sym = irGen.resolvedRefs[callee]
                if (sym !is FunctionSymbol) error("IRGen: 未解析的调用: ${callee.name}")
                val irCall = irGen.functionMethods[sym]?.let { IrMethodRef(it) }
                    ?: IrJvmCall(
                        name = sym.name,
                        owner = "yux.core.CoreLib",
                        static = true,
                        params = sym.params.map { TypeBridge.toIr(it.type) },
                        retType = TypeBridge.toIr(sym.returnType ?: SemaType.UnitT),
                    )
                g.emit(IrStmt.Call(irCall, null, args, irCall.retType))
            }
            is YxMemberAccess -> {
                val receiverType = irGen.exprTypes[callee.receiver]
                val rt = SemaType.resolveVar(receiverType ?: SemaType.ErrorT)
                if (callee.receiver is YxTypeReference) {
                    val static = irGen.resolver.resolveStaticMethod(rt, callee.name)
                        ?: error("IRGen: 静态方法不存在: ${callee.name}")
                    g.emit(IrStmt.Call(static, null, args, static.retType))
                    return
                }
                val receiver = exprGen.gen(callee.receiver, g, fileScope)
                val (irCall, receiverExpr) = resolveMemberCall(rt, callee.name, receiver, args)
                    ?: error("IRGen: 成员方法不存在: ${callee.name} on ${rt.render()}")
                g.emit(IrStmt.Call(irCall, receiverExpr, args, irCall.retType))
            }
            else -> error("IRGen: 不支持的调用目标: ${callee::class.simpleName}")
        }
    }

    /** 成员调用解析：返回 (IrCallable, receiver) 或 null。 */
    private fun resolveMemberCall(
        rt: SemaType,
        name: String,
        receiver: IrExpr,
        args: List<IrExpr>,
    ): Pair<yux.compiler.ir.IrCallable, IrExpr?>? = when {
        rt is SemaType.Declared && rt.symbol is YxClassSymbol -> {
            val sym = rt.symbol as YxClassSymbol
            sym.functionsNamed(name).firstOrNull()?.let { fn ->
                irGen.functionMethods[fn]?.let { IrMethodRef(it) to receiver }
            }
        }
        else -> irGen.resolver.resolveInstanceMethod(rt, name)?.let { it to receiver }
    }

    private fun genIf(stmt: YxIf, g: MethodGen, fileScope: FileScope) {
        val cond = exprGen.gen(stmt.condition, g, fileScope)
        val thenL = g.newLabel()
        val endL = g.newLabel()
        if (stmt.elseBranch == null) {
            g.emit(IrStmt.Branch(cond, thenL, endL))
            g.emit(IrStmt.Label(thenL))
            gen(stmt.thenBranch, g, fileScope)
            g.emit(IrStmt.Label(endL))
            return
        }
        val elseL = g.newLabel()
        g.emit(IrStmt.Branch(cond, thenL, elseL))
        g.emit(IrStmt.Label(thenL))
        gen(stmt.thenBranch, g, fileScope)
        g.emit(IrStmt.Goto(endL))
        g.emit(IrStmt.Label(elseL))
        gen(stmt.elseBranch, g, fileScope)
        g.emit(IrStmt.Label(endL))
    }

    private fun genWhile(stmt: YxWhile, g: MethodGen, fileScope: FileScope) {
        val condL = g.newLabel()
        val bodyL = g.newLabel()
        val endL = g.newLabel()
        g.emit(IrStmt.Label(condL))
        val cond = exprGen.gen(stmt.condition, g, fileScope)
        g.emit(IrStmt.Branch(cond, bodyL, endL))
        g.emit(IrStmt.Label(bodyL))
        g.pushLoop(condL, endL)
        gen(stmt.body, g, fileScope)
        g.popLoop()
        g.emit(IrStmt.Goto(condL))
        g.emit(IrStmt.Label(endL))
    }

    /** for 迭代器展开（T-M4-4 验收）：`for i in it {..}` → iterator()/hasNext()/next() 循环。 */
    private fun genFor(stmt: YxFor, g: MethodGen, fileScope: FileScope) {
        val iterableType = irGen.exprTypes[stmt.iterable]
        val rt = SemaType.resolveVar(iterableType ?: SemaType.ErrorT)
        val elemType = irGen.resolver.elementType(rt) ?: SemaType.ErrorT
        val iterable = exprGen.gen(stmt.iterable, g, fileScope)
        val iterLocal = g.newLocal("iter", TypeBridge.toIr(rt))
        g.emit(IrStmt.LocalAssign(iterLocal, iterable))
        val iteratorOwner = irGen.resolver.iteratorOwner(rt)
        val iteratorLocal = g.newLocal("iterator", IrType.Basic("Iterator"))
        g.emit(
            IrStmt.LocalAssign(
                iteratorLocal,
                IrExpr.Invoke(
                    IrJvmCall("iterator", iteratorOwner, static = false, params = emptyList(), retType = IrType.ANY),
                    IrExpr.LocalRead(iterLocal),
                    emptyList(),
                ),
            ),
        )

        val condL = g.newLabel()
        val bodyL = g.newLabel()
        val endL = g.newLabel()
        g.emit(IrStmt.Label(condL))
        val hasNext = g.newLocal("hasNext", IrType.BOOLEAN)
        g.emit(
            IrStmt.LocalAssign(
                hasNext,
                IrExpr.Invoke(
                    IrJvmCall("hasNext", "java.util.Iterator", static = false, params = emptyList(), retType = IrType.BOOLEAN),
                    IrExpr.LocalRead(iteratorLocal),
                    emptyList(),
                ),
            ),
        )
        g.emit(IrStmt.Branch(IrExpr.LocalRead(hasNext), bodyL, endL))
        g.emit(IrStmt.Label(bodyL))
        // 循环变量仅存在于循环体内（S-6.4.1 作用域）；进入嵌套作用域防泄漏/遮蔽
        g.enterScope()
        val varLocal = g.newLocal(stmt.varName, TypeBridge.toIr(elemType))
        g.declareLocal(stmt.varName, varLocal)
        g.emit(
            IrStmt.LocalAssign(
                varLocal,
                IrExpr.Invoke(
                    IrJvmCall("next", "java.util.Iterator", static = false, params = emptyList(), retType = TypeBridge.toIr(elemType)),
                    IrExpr.LocalRead(iteratorLocal),
                    emptyList(),
                ),
            ),
        )
        g.pushLoop(condL, endL)
        gen(stmt.body, g, fileScope)
        g.popLoop()
        g.exitScope()
        g.emit(IrStmt.Goto(condL))
        g.emit(IrStmt.Label(endL))
    }

    /** when → 分支链（subject 求值一次；`is T` 用 IsType；else 兜底）。 */
    private fun genWhen(stmt: YxWhen, g: MethodGen, fileScope: FileScope) {
        val subjectLocal = g.newLocal("subject", TypeBridge.toIr(irGen.exprTypes[stmt.subject] ?: SemaType.ErrorT))
        g.emit(IrStmt.LocalAssign(subjectLocal, exprGen.gen(stmt.subject, g, fileScope)))
        val endL = g.newLabel()
        for (branch in stmt.branches) {
            val bodyL = g.newLabel()
            when (val cond = branch.condition) {
                is YxElseCondition -> {
                    g.emit(IrStmt.Label(bodyL))
                    gen(branch.body, g, fileScope)
                    g.emit(IrStmt.Goto(endL))
                }
                is YxIsCondition -> {
                    val nextL = g.newLabel()
                    g.emit(
                        IrStmt.Branch(
                            IrExpr.IsType(IrExpr.LocalRead(subjectLocal), irGen.resolveIrType(cond.type, fileScope)),
                            bodyL,
                            nextL,
                        ),
                    )
                    g.emit(IrStmt.Label(bodyL))
                    gen(branch.body, g, fileScope)
                    g.emit(IrStmt.Goto(endL))
                    g.emit(IrStmt.Label(nextL))
                }
                is YxExprCondition -> {
                    val nextL = g.newLabel()
                    g.emit(
                        IrStmt.Branch(
                            IrExpr.Compare(CompareOp.EQ, IrExpr.LocalRead(subjectLocal), exprGen.gen(cond.expr, g, fileScope)),
                            bodyL,
                            nextL,
                        ),
                    )
                    g.emit(IrStmt.Label(bodyL))
                    gen(branch.body, g, fileScope)
                    g.emit(IrStmt.Goto(endL))
                    g.emit(IrStmt.Label(nextL))
                }
            }
        }
        g.emit(IrStmt.Label(endL))
    }

    private fun genTry(stmt: YxTry, g: MethodGen, fileScope: FileScope) {
        val body = genList(stmt.body, g, fileScope)
        val catches = stmt.catches.map { c ->
            val type = c.type?.let { irGen.resolveIrType(it, fileScope) }
            val bodyList = mutableListOf<IrStmt>()
            g.enterScope()
            // catch 参数登记为局部（S-6.6.2：catch 体内可引用异常变量）
            g.declareLocal(c.paramName, g.newLocal(c.paramName, type ?: IrType.ANY))
            g.withTarget(bodyList) { gen(c.body, g, fileScope) }
            g.exitScope()
            IrCatch(c.paramName, type, bodyList)
        }
        val finallyBody = stmt.finallyBody?.let { genList(it, g, fileScope) }
        g.emit(IrStmt.Try(body, catches, finallyBody))
    }

    /** 子块生成（Try/catch/finally 独立语句列表；单语句体与块体均适用）。 */
    private fun genList(stmt: YxStmt, g: MethodGen, fileScope: FileScope): List<IrStmt> {
        val out = mutableListOf<IrStmt>()
        g.enterScope()
        g.withTarget(out) { gen(stmt, g, fileScope) }
        g.exitScope()
        return out
    }

    /** 复合赋值运算符映射；`=` 返回 null（纯赋值），未知运算符显式报错。 */
    private fun compoundOp(op: String): ArithOp? = when (op) {
        "=" -> null
        "+=" -> ArithOp.ADD
        "-=" -> ArithOp.SUB
        "*=" -> ArithOp.MUL
        "/=" -> ArithOp.DIV
        "%=" -> ArithOp.MOD
        "..=" -> error("IRGen: `..=` 复合赋值不支持（M4）")
        else -> error("IRGen: 未知复合赋值运算符: $op")
    }
}
