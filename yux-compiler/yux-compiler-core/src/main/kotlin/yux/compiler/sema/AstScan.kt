package yux.compiler.sema

import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxStmt

/**
 * 轻量 AST 扫描工具：对函数体做不依赖符号表的预扫描（T-M3-6 前置）。
 */
object AstScan {

    /**
     * 收集函数体内作为**赋值目标**的标识符名（S-6.1.1 / 02-§7.3）。
     * 用于判定可变引用（声明后曾重新赋值 → 智能转型被抑制）。
     */
    fun assignmentTargetNames(body: YxFunctionBody): Set<String> {
        val names = mutableSetOf<String>()
        val exprs = when (body) {
            is YxFunctionBody.YxExpressionBody -> listOf(body.expr)
            is YxFunctionBody.YxBlockBody -> collectExprs(body.block)
        }
        for (e in exprs) {
            if (e is YxAssign) {
                val target = e.target
                if (target is YxIdentifier) names += target.name
            }
        }
        return names
    }

    /** 收集节点子树内全部表达式（含嵌套语句）。 */
    fun collectExprs(node: Any): List<YxExpr> {
        val out = mutableListOf<YxExpr>()
        visit(node) { if (it is YxExpr) out += it }
        return out
    }

    /** 泛型遍历：对每个 [YxExpr] 与 [YxStmt] 回调 [onNode]。 */
    fun visit(node: Any, onNode: (Any) -> Unit) {
        onNode(node)
        when (node) {
            is YxExpr -> visitExpr(node, onNode)
            is YxStmt -> visitStmt(node, onNode)
            is YxFunctionBody -> when (node) {
                is YxFunctionBody.YxExpressionBody -> visit(node.expr, onNode)
                is YxFunctionBody.YxBlockBody -> visit(node.block, onNode)
            }
            else -> Unit
        }
    }

    private fun visitExpr(e: YxExpr, onNode: (Any) -> Unit) {
        when (e) {
            is YxAssign -> {
                visit(e.target, onNode)
                visit(e.value, onNode)
            }
            is yux.compiler.ast.YxBlockLambda -> visit(e.body, onNode)
            is yux.compiler.ast.YxLambda -> visit(e.body, onNode)
            is yux.compiler.ast.YxStringTemplate -> e.parts.forEach { visit(it, onNode) }
            is yux.compiler.ast.YxMemberAccess -> visit(e.receiver, onNode)
            is yux.compiler.ast.YxCall -> {
                visit(e.callee, onNode)
                e.args.forEach { visit(it, onNode) }
            }
            is yux.compiler.ast.YxTypeCall -> e.args.forEach { visit(it, onNode) }
            is yux.compiler.ast.YxBinary -> {
                visit(e.left, onNode)
                visit(e.right, onNode)
            }
            is yux.compiler.ast.YxUnary -> visit(e.operand, onNode)
            is yux.compiler.ast.YxParen -> visit(e.expr, onNode)
            is yux.compiler.ast.YxIs -> visit(e.expr, onNode)
            is yux.compiler.ast.YxAs -> visit(e.expr, onNode)
            is yux.compiler.ast.YxNullable -> visit(e.expr, onNode)
            is yux.compiler.ast.YxRange -> {
                visit(e.from, onNode)
                visit(e.to, onNode)
            }
            is yux.compiler.ast.YxThrow -> visit(e.expr, onNode)
            is yux.compiler.ast.YxIfExpr -> {
                visit(e.condition, onNode)
                visit(e.thenExpr, onNode)
                visit(e.elseExpr, onNode)
            }
            is yux.compiler.ast.YxIndexExpr -> {
                visit(e.base, onNode)
                visit(e.index, onNode)
            }
            is yux.compiler.ast.YxTypeReference -> Unit
            is yux.compiler.ast.YxIdentifier,
            is yux.compiler.ast.YxIntLiteral,
            is yux.compiler.ast.YxFloatLiteral,
            is yux.compiler.ast.YxCharLiteral,
            is yux.compiler.ast.YxBoolLiteral,
            is yux.compiler.ast.YxNullLiteral,
            is yux.compiler.ast.YxStringLiteral,
            is yux.compiler.ast.YxRawStringLiteral,
            is yux.compiler.ast.YxStringText,
            is yux.compiler.ast.YxThis,
            is yux.compiler.ast.YxSuper -> Unit
        }
    }

    private fun visitStmt(s: YxStmt, onNode: (Any) -> Unit) {
        when (s) {
            is YxAssign -> {
                visit(s.target, onNode)
                visit(s.value, onNode)
            }
            is yux.compiler.ast.YxBlock -> s.statements.forEach { visit(it, onNode) }
            is yux.compiler.ast.YxVarDecl -> s.initializer?.let { visit(it, onNode) }
            is yux.compiler.ast.YxExprStmt -> visit(s.expr, onNode)
            is yux.compiler.ast.YxIf -> {
                visit(s.condition, onNode)
                visit(s.thenBranch, onNode)
                s.elseBranch?.let { visit(it, onNode) }
            }
            is yux.compiler.ast.YxWhen -> {
                visit(s.subject, onNode)
                s.branches.forEach { visit(it.body, onNode) }
            }
            is yux.compiler.ast.YxFor -> {
                visit(s.iterable, onNode)
                visit(s.body, onNode)
            }
            is yux.compiler.ast.YxWhile -> {
                visit(s.condition, onNode)
                visit(s.body, onNode)
            }
            is yux.compiler.ast.YxReturn -> s.value?.let { visit(it, onNode) }
            is yux.compiler.ast.YxBreak,
            is yux.compiler.ast.YxContinue -> Unit
            is yux.compiler.ast.YxTry -> {
                visit(s.body, onNode)
                s.catches.forEach { visit(it.body, onNode) }
                s.finallyBody?.let { visit(it, onNode) }
            }
            is yux.compiler.ast.YxAsyncBlock -> visit(s.body, onNode)
            is yux.compiler.ast.YxParallelBlock -> visit(s.body, onNode)
            is yux.compiler.ast.YxUnsafeBlock -> visit(s.body, onNode)
        }
    }
}
