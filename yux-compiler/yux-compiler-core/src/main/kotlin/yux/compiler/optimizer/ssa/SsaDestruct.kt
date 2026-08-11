package yux.compiler.optimizer.ssa

import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrStmt

/**
 * SSA 销毁：φ 节点落地为前驱块末尾的拷贝（真 SSA 版本局部互不共槽，拷贝均有效）：
 *
 * - **多后继前驱**（Branch）需**边分裂**：在 p→b 边插入单语句分裂块，拷贝放入其中，
 *   并把分支的该侧目标重定向到分裂块；
 * - **并行拷贝防护**：同一组拷贝内若某源版本也是另一拷贝的目标（φ 环），改用
 *   两阶段（先全部读入临时，再写目标），杜绝顺序拷贝覆盖源值；
 * - 单后继前驱（Goto/落空）直接追加到块体末尾。
 *
 * 销毁后按块序线性化：[label] → 块体 → 终结语句；落空块仅在其后继不是「下一块」
 * 时补显式 Goto。
 */
internal class SsaDestruct(private val cfg: Cfg) {
    private var nextLabelSeq = 0
    private var nextTempIndex = 0

    init {
        nextTempIndex = maxLocalIndex() + 1
    }

    fun run() {
        for (b in cfg.blocks.toList()) {
            if (b.phis.isEmpty()) continue
            for (p in b.pred.toList()) {
                val copies = b.phis.mapNotNull { phi ->
                    val incoming = phi.incoming.firstOrNull { it.first === p }?.second
                    val v = (incoming as? IrExpr.LocalRead)?.local ?: return@mapNotNull null
                    if (v === phi.target) null else phi.target to v
                }
                if (copies.isEmpty()) continue
                if (p.succ.size > 1) {
                    splitEdge(p, b, copies)
                } else {
                    p.stmts += makeCopies(copies)
                }
            }
            b.phis.clear()
        }
    }

    /** 线性化输出（与后端兼容的 Label/Goto/Branch 形式）。 */
    fun emit(): MutableList<IrStmt> {
        val out = mutableListOf<IrStmt>()
        val blocks = cfg.blocks
        for (i in blocks.indices) {
            val b = blocks[i]
            b.label?.let { out += IrStmt.Label(it) }
            out += b.stmts
            when (val t = b.terminator) {
                is IrStmt.Nop -> {
                    val next = blocks.getOrNull(i + 1)
                    val succ = b.succ.firstOrNull()
                    if (next !== succ && succ?.label != null) {
                        out += IrStmt.Goto(succ.label!!)
                    }
                }
                else -> out += b.terminator!!
            }
        }
        return out
    }

    // ── 边分裂 ──────────────────────────────────────────────────────────────

    private fun splitEdge(p: Block, b: Block, copies: List<Pair<IrLocal, IrLocal>>) {
        val splitLabel = IrLabel("L\$ssa\$${nextLabelSeq++}")
        val split = Block(label = splitLabel)
        split.stmts += makeCopies(copies)
        split.terminator = IrStmt.Goto(b.label!!)

        val term = p.terminator as? IrStmt.Branch ?: return
        p.terminator = when {
            term.then == b.label -> term.copy(then = splitLabel)
            term.elseLabel == b.label -> term.copy(elseLabel = splitLabel)
            else -> term
        }

        p.succ.remove(b)
        p.succ.add(split)
        b.pred.remove(p)
        b.pred.add(split)
        split.pred += p
        split.succ += b
        cfg.blocks.add(cfg.blocks.indexOf(p) + 1, split)
    }

    // ── 拷贝序列 ─────────────────────────────────────────────────────────────

    private fun makeCopies(copies: List<Pair<IrLocal, IrLocal>>): List<IrStmt> {
        val sources = copies.map { it.second }.toSet()
        val targets = copies.map { it.first }.toSet()
        val hasCycle = copies.any { (_, s) -> s in targets }
        if (!hasCycle) {
            return copies.map { (t, s) -> IrStmt.LocalAssign(t, IrExpr.LocalRead(s)) }
        }
        val temps = copies.map { (t, _) -> IrLocal(t.name + "\$phi", t.type, nextTempIndex++) }
        return copies.flatMapIndexed { i, (t, s) ->
            listOf(
                IrStmt.LocalAssign(temps[i], IrExpr.LocalRead(s)),
                IrStmt.LocalAssign(t, IrExpr.LocalRead(temps[i])),
            )
        }
    }

    private fun maxLocalIndex(): Int {
        var max = -1
        fun scanExpr(e: IrExpr?) {
            if (e == null) return
            when (e) {
                is IrExpr.LocalRead -> max = maxOf(max, e.local.index)
                is IrExpr.FieldRead -> scanExpr(e.receiver)
                is IrExpr.New -> e.args.forEach { scanExpr(it) }
                is IrExpr.ArrayLoad -> { scanExpr(e.base); scanExpr(e.index) }
                is IrExpr.Arith -> { scanExpr(e.l); scanExpr(e.r) }
                is IrExpr.Compare -> { scanExpr(e.l); scanExpr(e.r) }
                is IrExpr.Not -> scanExpr(e.operand)
                is IrExpr.Neg -> scanExpr(e.operand)
                is IrExpr.Convert -> scanExpr(e.expr)
                is IrExpr.IsType -> scanExpr(e.expr)
                is IrExpr.Invoke -> { scanExpr(e.receiver); e.args.forEach { scanExpr(it) } }
                is IrExpr.FnInvoke -> { scanExpr(e.fn); e.args.forEach { scanExpr(it) } }
                is IrExpr.StringTemplate -> e.parts.forEach { scanExpr(it) }
                is IrExpr.NullGuard -> scanExpr(e.expr)
                is IrExpr.Lambda -> e.captures.forEach { scanExpr(it) }
                else -> Unit
            }
        }
        fun scanStmt(s: IrStmt) {
            when (s) {
                is IrStmt.LocalAssign -> { max = maxOf(max, s.local.index); scanExpr(s.value) }
                is IrStmt.Call -> { scanExpr(s.receiver); s.args.forEach { scanExpr(it) } }
                is IrStmt.New -> s.args.forEach { scanExpr(it) }
                is IrStmt.Eval -> scanExpr(s.expr)
                is IrStmt.FieldAccess -> { scanExpr(s.receiver); scanExpr(s.value) }
                is IrStmt.Branch -> scanExpr(s.cond)
                is IrStmt.Return -> scanExpr(s.value)
                is IrStmt.Throw -> scanExpr(s.value)
                is IrStmt.Monitor -> scanExpr(s.expr)
                is IrStmt.Await -> scanExpr(s.target)
                is IrStmt.Try -> {
                    s.body.forEach { scanStmt(it) }
                    s.catches.forEach { it.body.forEach { c -> scanStmt(c) } }
                    s.finallyBody?.forEach { scanStmt(it) }
                }
                else -> Unit
            }
        }
        for (b in cfg.blocks) {
            for (s in b.stmts) scanStmt(s)
            scanStmt(b.terminator ?: continue)
        }
        return max
    }
}
