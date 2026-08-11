package yux.compiler.optimizer

import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrStmt
import yux.compiler.optimizer.ssa.CfgBuilder
import yux.compiler.optimizer.ssa.Sccp
import yux.compiler.optimizer.ssa.SsaBuilder
import yux.compiler.optimizer.ssa.SsaCleanup
import yux.compiler.optimizer.ssa.SsaDestruct
import yux.compiler.optimizer.ssa.readLocals
import yux.compiler.optimizer.ssa.tryRegionSelfContained

/**
 * 完整 SSA 优化器（02-§8.4 的「完整 SSA/优化框架」，v0.1 后置项落地）：
 *
 * 每个方法体的管线：CFG 构造 → φ 插入 + 重命名（SSA）→ SCCP（全局常量传播 +
 * 分支折叠）→ 常量/拷贝应用 → 拷贝传播 + 平凡 φ 消除 → 激进 DCE → φ 销毁
 * （边分裂拷贝）→ 线性化回退为后端可用的 Label/Goto/Branch 形式。
 *
 * **保守回退**（保持正确性，交回 BasicOpt）：
 * - 方法体含 [IrStmt.Await]（CPS 降级前不应出现，防御）；
 * - Try 子列表向闭包外层逃逸（break/continue 跨 try 等，见
 *   [tryRegionSelfContained]）。
 */
object SsaOpt {

    fun optimize(module: IrModule) {
        for (cls in module.classes) {
            for (method in cls.methods) {
                optimizeMethod(method)
            }
        }
    }

    private fun optimizeMethod(method: IrMethod) {
        val body = method.body
        if (body.isEmpty()) return
        if (containsAwait(body)) return
        if (body.any { it is IrStmt.Try && !tryRegionSelfContained(it) }) return

        val originalLocals = collectLocals(body) + method.paramLocals
        val cfg = CfgBuilder().build(body)
        SsaBuilder(cfg, method.paramLocals, maxIndex(originalLocals) + 1).build()
        val sccp = Sccp(cfg)
        sccp.run()
        sccp.apply()
        SsaCleanup(cfg, originalLocals).run()
        val destruct = SsaDestruct(cfg)
        destruct.run()
        val out = destruct.emit()

        body.clear()
        body.addAll(out)
    }

    // ── 预检 ────────────────────────────────────────────────────────────────

    private fun containsAwait(stmts: List<IrStmt>): Boolean {
        for (s in stmts) {
            when (s) {
                is IrStmt.Await -> return true
                is IrStmt.Try ->
                    if (containsAwait(s.body) ||
                        s.catches.any { containsAwait(it.body) } ||
                        (s.finallyBody?.let { containsAwait(it) } ?: false)
                    ) return true
                else -> Unit
            }
        }
        return false
    }

    // ── 原局部收集（flush 拷贝识别用）───────────────────────────────────────

    private fun collectLocals(stmts: List<IrStmt>): Set<IrLocal> {
        val out = HashSet<IrLocal>()
        fun walkStmt(s: IrStmt) {
            when (s) {
                is IrStmt.LocalAssign -> {
                    out += s.local
                    readLocals(s.value, out)
                }
                is IrStmt.Call -> {
                    readLocals(s.receiver, out)
                    s.args.forEach { readLocals(it, out) }
                }
                is IrStmt.New -> s.args.forEach { readLocals(it, out) }
                is IrStmt.Eval -> readLocals(s.expr, out)
                is IrStmt.FieldAccess -> {
                    readLocals(s.receiver, out)
                    readLocals(s.value, out)
                }
                is IrStmt.Branch -> readLocals(s.cond, out)
                is IrStmt.Return -> readLocals(s.value, out)
                is IrStmt.Throw -> readLocals(s.value, out)
                is IrStmt.Monitor -> readLocals(s.expr, out)
                is IrStmt.Await -> {
                    out += s.local
                    readLocals(s.target, out)
                }
                is IrStmt.Try -> {
                    s.body.forEach { walkStmt(it) }
                    s.catches.forEach { it.body.forEach { c -> walkStmt(c) } }
                    s.finallyBody?.forEach { walkStmt(it) }
                }
                else -> Unit
            }
        }
        stmts.forEach { walkStmt(it) }
        return out
    }

    private fun maxIndex(locals: Set<IrLocal>): Int =
        locals.maxOfOrNull { it.index } ?: -1
}
