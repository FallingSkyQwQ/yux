package yux.compiler.irgen

import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAwait
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
import yux.compiler.ast.YxWhenExpr
import yux.compiler.ast.YxWhile
import yux.compiler.sema.ParameterSymbol
import yux.compiler.sema.Symbol
import yux.compiler.sema.VariableSymbol

/**
 * `async { }` / `parallel { }` 块静态分析（S-8.4）：捕获收集 + 拆分安全性 + 合法性检查。
 * 合法性检查把非法结构（return / 块顶层 break/continue / 写外部局部）在编译期拒绝，
 * 避免 lambda 化后静默改变语义（写回丢失、return 截断函数、break 失效）。
 */
class ConcurrentBlockAnalysis(
    private val irGen: IRGen,
    private val exprGen: ExprGen,
) {

    /** 块级局部名（顶层 YxVarDecl）。 */
    internal fun blockLocals(block: YxBlock): Set<String> =
        block.statements.mapNotNull { (it as? YxVarDecl)?.name }.toSet()

    /** 块内自由变量捕获（B2 语义：块级局部已绑定，外层局部按值捕获）。 */
    internal fun capturesOf(block: YxBlock): List<Pair<String, Symbol>> {
        val bound = mutableSetOf<String>()
        exprGen.prescanBlockVars(block, bound)
        val captures = mutableListOf<Pair<String, Symbol>>()
        exprGen.collectCaptures(block, bound, captures)
        return captures
    }

    /** 拆分安全性：顶层语句互不引用块级局部（引用则需共享局部，退化为单任务）。 */
    internal fun splitSafe(block: YxBlock): Boolean {
        val locals = blockLocals(block)
        for (stmt in block.statements) {
            // 每条顶层语句编译为独立方法：bound 每语句重置（块级局部不属于当前语句的作用域）
            if (readsBlockLocal(stmt, locals, HashSet())) return false
        }
        return true
    }

    private fun readsBlockLocal(stmt: YxStmt, locals: Set<String>, bound: MutableSet<String>): Boolean = when (stmt) {
        is YxVarDecl -> {
            val init = stmt.initializer?.let { readsBlockLocalExpr(it, locals, bound) } ?: false
            bound += stmt.name
            init
        }
        is YxAssign -> {
            val t = stmt.target
            val targetHit = if (t is YxIdentifier) {
                hitBlockLocal(t, locals, bound)
            } else {
                readsBlockLocalExpr(t, locals, bound)
            }
            targetHit || readsBlockLocalExpr(stmt.value, locals, bound)
        }
        is YxExprStmt -> readsBlockLocalExpr(stmt.expr, locals, bound)
        is YxBlock -> {
            val saved = HashSet(bound)
            stmt.statements.any { readsBlockLocal(it, locals, saved) }
        }
        is YxIf -> readsBlockLocalExpr(stmt.condition, locals, bound) ||
            readsBlockLocal(stmt.thenBranch, locals, bound) ||
            (stmt.elseBranch?.let { readsBlockLocal(it, locals, bound) } ?: false)
        is YxWhile -> readsBlockLocalExpr(stmt.condition, locals, bound) ||
            readsBlockLocal(stmt.body, locals, bound)
        is YxFor -> readsBlockLocalExpr(stmt.iterable, locals, bound) ||
            readsBlockLocal(stmt.body, locals, bound)
        is YxWhen -> stmt.branches.any { readsBlockLocal(it.body, locals, bound) }
        is YxTry -> readsBlockLocal(stmt.body, locals, bound) ||
            stmt.catches.any { readsBlockLocal(it.body, locals, bound) } ||
            (stmt.finallyBody?.let { readsBlockLocal(it, locals, bound) } ?: false)
        else -> false
    }

    private fun readsBlockLocalExpr(expr: YxExpr, locals: Set<String>, bound: MutableSet<String>): Boolean = when (expr) {
        is YxIdentifier -> hitBlockLocal(expr, locals, bound)
        is YxMemberAccess -> readsBlockLocalExpr(expr.receiver, locals, bound)
        is YxCall -> readsBlockLocalExpr(expr.callee, locals, bound) || expr.args.any { readsBlockLocalExpr(it, locals, bound) }
        is YxTypeCall -> expr.args.any { readsBlockLocalExpr(it, locals, bound) }
        is YxBinary -> readsBlockLocalExpr(expr.left, locals, bound) || readsBlockLocalExpr(expr.right, locals, bound)
        is YxUnary -> readsBlockLocalExpr(expr.operand, locals, bound)
        is YxParen -> readsBlockLocalExpr(expr.expr, locals, bound)
        is YxNullable -> readsBlockLocalExpr(expr.expr, locals, bound)
        is YxIs -> readsBlockLocalExpr(expr.expr, locals, bound)
        is YxAs -> readsBlockLocalExpr(expr.expr, locals, bound)
        is YxRange -> readsBlockLocalExpr(expr.from, locals, bound) || readsBlockLocalExpr(expr.to, locals, bound)
        is YxStringTemplate -> expr.parts.any { readsBlockLocalExpr(it, locals, bound) }
        is YxIndexExpr -> readsBlockLocalExpr(expr.base, locals, bound) || readsBlockLocalExpr(expr.index, locals, bound)
        is YxAssign -> readsBlockLocalExpr(expr.target, locals, bound) || readsBlockLocalExpr(expr.value, locals, bound)
        is YxAwait -> readsBlockLocalExpr(expr.operand, locals, bound)
        is YxLambda -> {
            val saved = HashSet(bound)
            saved += expr.params
            readsBlockLocalExpr(expr.body, locals, saved)
        }
        is YxBlockLambda -> {
            val saved = HashSet(bound)
            saved += "it"
            saved += blockLocals(expr.body)
            expr.body.statements.any { readsBlockLocal(it, locals, saved) }
        }
        is YxIfExpr -> readsBlockLocalExpr(expr.condition, locals, bound) ||
            readsBlockLocalExpr(expr.thenExpr, locals, bound) ||
            readsBlockLocalExpr(expr.elseExpr, locals, bound)
        is YxWhenExpr -> (expr.subject?.let { readsBlockLocalExpr(it, locals, bound) } ?: false) ||
            expr.branches.any { readsBlockLocalExpr(it.body, locals, bound) }
        else -> false
    }

    private fun hitBlockLocal(id: YxIdentifier, locals: Set<String>, bound: MutableSet<String>): Boolean {
        val sym = irGen.resolvedRefs[id]
        return sym is VariableSymbol && sym.name in locals && sym.name !in bound
    }

    /**
     * 并发块合法性检查（编译期错误而非静默错误语义）：
     * - `return` 会从外层函数返回（lambda 化后语义改变）→ 拒绝；
     * - 块顶层 `break`/`continue` 作用于外层循环（lambda 化后丢失）→ 拒绝；
     * - 写外部局部变量（lambda 捕获为值语义，写回丢失）→ 拒绝。
     */
    internal fun checkConcurrentBlock(block: YxBlock) {
        val locals = blockLocals(block)
        val bound = HashSet<String>()
        for (stmt in block.statements) {
            checkStmt(stmt, locals, bound, loopDepth = 0)
        }
    }

    private fun checkStmt(stmt: YxStmt, locals: Set<String>, bound: MutableSet<String>, loopDepth: Int) {
        when (stmt) {
            is YxVarDecl -> {
                stmt.initializer?.let { checkExpr(it, locals, bound) }
                bound += stmt.name
            }
            is YxAssign -> {
                val t = stmt.target
                if (t is YxIdentifier) {
                    val sym = irGen.resolvedRefs[t]
                    if ((sym is VariableSymbol || sym is ParameterSymbol) && sym.name !in bound && sym.name !in locals) {
                        throw IllegalStateException(
                            "async/parallel 块内不能修改外部局部变量 '${sym.name}'（lambda 捕获为值语义，写回会丢失）",
                        )
                    }
                } else {
                    checkExpr(t, locals, bound)
                }
                checkExpr(stmt.value, locals, bound)
            }
            is YxExprStmt -> checkExpr(stmt.expr, locals, bound)
            is YxReturn -> throw IllegalStateException("async/parallel 块内不支持 return（块编译为 lambda，v0.1）")
            is YxBreak, is YxContinue -> if (loopDepth == 0) {
                throw IllegalStateException("async/parallel 块内 ${if (stmt is YxBreak) "break" else "continue"} 作用于外层循环，v0.1 不支持")
            }
            is YxBlock -> stmt.statements.forEach { checkStmt(it, locals, bound, loopDepth) }
            is YxIf -> {
                checkExpr(stmt.condition, locals, bound)
                checkStmt(stmt.thenBranch, locals, bound, loopDepth)
                stmt.elseBranch?.let { checkStmt(it, locals, bound, loopDepth) }
            }
            is YxWhile -> {
                checkExpr(stmt.condition, locals, bound)
                checkStmt(stmt.body, locals, bound, loopDepth + 1)
            }
            is YxFor -> {
                checkExpr(stmt.iterable, locals, bound)
                checkStmt(stmt.body, locals, bound, loopDepth + 1)
            }
            is YxWhen -> stmt.branches.forEach { checkStmt(it.body, locals, bound, loopDepth) }
            is YxTry -> {
                checkStmt(stmt.body, locals, bound, loopDepth)
                stmt.catches.forEach { checkStmt(it.body, locals, bound, loopDepth) }
                stmt.finallyBody?.let { checkStmt(it, locals, bound, loopDepth) }
            }
            else -> Unit
        }
    }

    private fun checkExpr(expr: YxExpr, locals: Set<String>, bound: MutableSet<String>) {
        when (expr) {
            is YxIdentifier -> Unit // 读外部局部 = 按值捕获（合法）
            is YxMemberAccess -> checkExpr(expr.receiver, locals, bound)
            is YxCall -> {
                checkExpr(expr.callee, locals, bound)
                expr.args.forEach { checkExpr(it, locals, bound) }
            }
            is YxTypeCall -> expr.args.forEach { checkExpr(it, locals, bound) }
            is YxBinary -> {
                checkExpr(expr.left, locals, bound)
                checkExpr(expr.right, locals, bound)
            }
            is YxUnary -> checkExpr(expr.operand, locals, bound)
            is YxParen -> checkExpr(expr.expr, locals, bound)
            is YxNullable -> checkExpr(expr.expr, locals, bound)
            is YxIs -> checkExpr(expr.expr, locals, bound)
            is YxAs -> checkExpr(expr.expr, locals, bound)
            is YxRange -> {
                checkExpr(expr.from, locals, bound)
                checkExpr(expr.to, locals, bound)
            }
            is YxStringTemplate -> expr.parts.forEach { checkExpr(it, locals, bound) }
            is YxIndexExpr -> {
                checkExpr(expr.base, locals, bound)
                checkExpr(expr.index, locals, bound)
            }
            is YxAssign -> {
                checkExpr(expr.target, locals, bound)
                checkExpr(expr.value, locals, bound)
            }
            is YxAwait -> checkExpr(expr.operand, locals, bound)
            is YxLambda -> {
                val saved = HashSet(bound)
                saved += expr.params
                checkExpr(expr.body, locals, saved)
            }
            is YxBlockLambda -> {
                val saved = HashSet(bound)
                saved += "it"
                saved += blockLocals(expr.body)
                expr.body.statements.forEach { checkStmt(it, locals, saved, loopDepth = 0) }
            }
            is YxIfExpr -> {
                checkExpr(expr.condition, locals, bound)
                checkExpr(expr.thenExpr, locals, bound)
                checkExpr(expr.elseExpr, locals, bound)
            }
            is YxWhenExpr -> {
                expr.subject?.let { checkExpr(it, locals, bound) }
                expr.branches.forEach { checkExpr(it.body, locals, bound) }
            }
            else -> Unit
        }
    }
}
