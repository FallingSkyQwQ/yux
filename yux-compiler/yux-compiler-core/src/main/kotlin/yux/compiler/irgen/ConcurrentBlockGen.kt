package yux.compiler.irgen

import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBreak
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxContinue
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxFor
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIf
import yux.compiler.ast.YxIfExpr
import yux.compiler.ast.YxIndexExpr
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxRange
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxStmt
import yux.compiler.ast.YxStringTemplate
import yux.compiler.ast.YxTry
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxUnary
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhile
import yux.compiler.ir.ArithOp
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.FileScope
import yux.compiler.sema.ParameterSymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.Symbol
import yux.compiler.sema.VariableSymbol

/**
 * `async { }` / `parallel { }` 块并发编译（S-8.4，T-M11-2）：块体编译为 Function0 lambda
 * 经 yux.async.Tasks 提交（launch 不阻塞 / parallelAll 并发 + 块尾 join / parallel 单任务）。
 *
 * 非法结构在编译期拒绝（不再静默内联）：return（lambda 化后语义改变）、块顶层 break/continue
 * （作用于外层循环）、写外部局部变量（lambda 捕获为值语义，写回丢失）。
 */
class ConcurrentBlockGen(
    private val stmtGen: StmtGen,
    private val irGen: IRGen,
) {
    private val analysis = ConcurrentBlockAnalysis(irGen, stmtGen.exprGen)

    // ── async / parallel 块（S-8.4，T-M11-2 真并发）──────────────────────────

    /**
     * `async { }`：块体编译为合成 async fun（isLaunchedAsync 门面），经
     * [yux.async.Continuations.launch] 在 ForkJoinPool 上驱动状态机并立即返回 Task
     * （不阻塞主线程；S-8.4.1，T-M14）。块体内可 `await`（协程上下文）。
     * 外层局部变量按值捕获（快照）。
     */
    internal fun genAsyncBlock(block: YxBlock, g: MethodGen, fileScope: FileScope) {
        analysis.checkConcurrentBlock(block)
        val captures = analysis.capturesOf(block)
        val facade = irGen.newAsyncBlockMethod(captures, block, g, fileScope)
        val args = captures.map { (name, _) ->
            IrExpr.LocalRead(g.lookupLocal(name) ?: error("IRGen: async{} 捕获变量未登记: $name"))
        }
        g.emit(IrStmt.Eval(IrExpr.Invoke(IrMethodRef(facade), null, args)))
    }

    /**
     * `parallel { }`：顶层互相独立的语句各编译为一个任务提交到 ForkJoinPool 并发执行，
     * 块尾隐式 join（S-8.4.3）；块内语句引用块级局部（互相依赖）时退化为单任务
     * （整块一个任务在池中执行，仍阻塞至完成）。
     */
    internal fun genParallelBlock(block: YxBlock, g: MethodGen, fileScope: FileScope) {
        analysis.checkConcurrentBlock(block)
        if (block.statements.size <= 1 || !analysis.splitSafe(block)) {
            genSingleTaskBlock(block, g, fileScope, owner = "parallel")
            return
        }
        val blockLocals = analysis.blockLocals(block)
        val captures = analysis.capturesOf(block)
        val arrayList = irGen.resolver.resolveJvmClass("java.util.ArrayList")
            ?: error("IRGen: 无法解析 java.util.ArrayList")
        val listType = IrType.Declared(arrayList, emptyList())
        val tasks = g.newLocal("tasks", listType)
        g.emit(IrStmt.LocalAssign(tasks, IrExpr.New(listType, emptyList())))
        val add = IrJvmCall(
            name = "add",
            owner = "java.util.ArrayList",
            static = false,
            params = listOf(IrType.ANY),
            retType = IrType.BOOLEAN,
        )
        val lambdas = block.statements.map { stmt ->
            val method = newBlockLambdaMethod(captures, g)
            val lambdaG = MethodGen(method, g.owner)
            lambdaG.enterScope()
            stmtGen.gen(stmt, lambdaG, fileScope)
            lambdaG.emit(IrStmt.Return(IrExpr.Const(null)))
            lambdaG.exitScope()
            val lambda = IrExpr.Lambda(IrMethodRef(method), captureReads(captures, g))
            g.emit(IrStmt.Call(add, IrExpr.LocalRead(tasks), listOf(lambda), IrType.BOOLEAN))
            lambda
        }
        val parallelAll = IrJvmCall(
            name = "parallelAll",
            owner = "yux.async.Tasks",
            static = true,
            params = listOf(IrType.Generic("List", emptyList())),
            retType = IrType.Void,
        )
        g.emit(IrStmt.Call(parallelAll, null, listOf(IrExpr.LocalRead(tasks)), IrType.Void))
    }

    /** 单任务模式：整块一个 lambda，经 Tasks.parallel/launch 在池中执行。 */
    private fun genSingleTaskBlock(block: YxBlock, g: MethodGen, fileScope: FileScope, owner: String) {
        val captures = analysis.capturesOf(block)
        val method = newBlockLambdaMethod(captures, g)
        val lambdaG = MethodGen(method, g.owner)
        lambdaG.enterScope()
        block.statements.forEach { stmtGen.gen(it, lambdaG, fileScope) }
        lambdaG.emit(IrStmt.Return(IrExpr.Const(null)))
        lambdaG.exitScope()
        val lambda = IrExpr.Lambda(IrMethodRef(method), captureReads(captures, g))
        val call = IrJvmCall(
            name = owner,
            owner = "yux.async.Tasks",
            static = true,
            params = listOf(IrType.Function(emptyList(), IrType.Void)),
            retType = IrType.Void,
        )
        g.emit(IrStmt.Call(call, null, listOf(lambda), IrType.Void))
    }

    /** 块级 lambda 合成方法：捕获参数在前，SAM 擦除返回 Object（体以 Return(null) 收尾）。 */
    private fun newBlockLambdaMethod(captures: List<Pair<String, Symbol>>, g: MethodGen): IrMethod =
        irGen.newLambdaMethod(
            name = irGen.nextLambdaName(),
            params = captures.map { (name, sym) -> IrParam(name, captureIrType(sym)) },
            returnType = IrType.ANY,
            isStatic = g.method.isStatic,
            owner = g.owner,
        )

    /** 捕获读取：外层局部（快照于 lambda 创建时）。 */
    private fun captureReads(captures: List<Pair<String, Symbol>>, g: MethodGen): List<IrExpr> =
        captures.map { (name, _) ->
            IrExpr.LocalRead(g.lookupLocal(name) ?: error("IRGen: 捕获变量未登记: $name"))
        }

    private fun captureIrType(sym: Symbol): IrType =
        TypeBridge.toIr(stmtGen.symbolType(sym) ?: SemaType.ErrorT)
}

