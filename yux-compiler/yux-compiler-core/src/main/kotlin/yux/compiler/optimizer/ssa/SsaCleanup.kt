package yux.compiler.optimizer.ssa

import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrStmt

/**
 * SSA 清理趟（SCCP/apply 之后）：
 *
 * 1. **拷贝传播**：`x = y`（及平凡 φ）→ 用 `y` 替换 `x` 的所有读取（SSA 下
 *    `y` 的定义支配 `x` 的拷贝，故安全）；拷贝链经 [resolve] 展开（含环防护）；
 * 2. **平凡 φ 消除**：φ 的全部入边为同一版本 → 用该版本替换目标读取；
 * 3. **激进死代码消除**：不可达块删除；以「副作用/可抛异常/终结语句」为根，
 *    沿 SSA 使用链向后传播活性（φ 视为活跃则其入边版本活跃），清扫死赋值。
 *
 * Try 前的 flush 拷贝（目标是 [originalLocals] 中的原局部）视为根：其为 Try
 * 内原寄存器初始化，必须保留。
 */
internal class SsaCleanup(
    private val cfg: Cfg,
    private val originalLocals: Set<IrLocal>,
) {

    /** 被结构化 Try 写入的原局部：其原寄存器可被 flush/内层代码改写，不可作传播源。 */
    private val tryWritten: Set<IrLocal> = HashSet<IrLocal>().also { all ->
        for (b in cfg.blocks) {
            for (s in b.stmts) {
                if (s is IrStmt.Try) all += tryDefs(s)
            }
        }
    }

    fun run() {
        copyPropagation()
        trivialPhis()
        dce()
    }

    // ── 拷贝传播 ────────────────────────────────────────────────────────────

    private fun copyPropagation() {
        val copyOf = HashMap<IrLocal, IrLocal>()
        for (b in cfg.blocks) {
            for (stmt in b.stmts) {
                if (stmt is IrStmt.LocalAssign) {
                    val v = stmt.value
                    // flush 拷贝目标（原局部）不可传播；被 Try 写入的原局部作源亦不可
                    // 传播（其寄存器可被后续 flush/内层代码改写，传播会破坏值）
                    if (v is IrExpr.LocalRead &&
                        stmt.local !in originalLocals &&
                        (v.local !in originalLocals || v.local !in tryWritten)
                    ) {
                        copyOf[stmt.local] = v.local
                    }
                }
            }
        }
        fun resolve(x: IrLocal): IrLocal {
            val seen = HashSet<IrLocal>()
            var cur = x
            while (true) {
                if (cur in seen) return cur
                seen += cur
                cur = copyOf[cur] ?: return cur
            }
        }
        for (b in cfg.blocks) {
            for (i in b.stmts.indices) {
                b.stmts[i] = mapReadsInStatement(b.stmts[i]) { resolve(it) }
            }
            for (phi in b.phis) {
                for (j in phi.incoming.indices) {
                    val (p, e) = phi.incoming[j]
                    if (e is IrExpr.LocalRead) {
                        val r = resolve(e.local)
                        if (r !== e.local) phi.incoming[j] = p to IrExpr.LocalRead(r)
                    }
                }
            }
            b.terminator = mapReadsInTerminator(b.terminator!!) { resolve(it) }
        }
    }

    // ── 平凡 φ 消除 ──────────────────────────────────────────────────────────

    private fun trivialPhis() {
        val resolved = HashMap<IrLocal, IrLocal>()
        fun resolve(v: IrLocal): IrLocal {
            var cur = v
            val seen = HashSet<IrLocal>()
            while (true) {
                if (cur in seen) return cur
                seen += cur
                cur = resolved[cur] ?: return cur
            }
        }
        for (b in cfg.blocks) {
            val it = b.phis.iterator()
            while (it.hasNext()) {
                val phi = it.next()
                val incoming = phi.incoming.map { (it.second as? IrExpr.LocalRead)?.local }
                val first = incoming.firstOrNull()
                // 平凡 φ 消除：全部入边同一版本时用其替换目标读取；
                // 被 Try 写入的原局部不可作为替换源（其寄存器可被后续改写）
                if (first != null &&
                    (first !in originalLocals || first !in tryWritten) &&
                    incoming.all { it == first }
                ) {
                    resolved[phi.target] = first
                    it.remove()
                }
            }
        }
        if (resolved.isEmpty()) return
        for (b in cfg.blocks) {
            for (i in b.stmts.indices) {
                b.stmts[i] = mapReadsInStatement(b.stmts[i]) { resolve(it) }
            }
            b.terminator = mapReadsInTerminator(b.terminator!!) { resolve(it) }
        }
    }

    // ── 死代码消除 ──────────────────────────────────────────────────────────

    private fun dce() {
        val reach = reachableBlocks()
        val defStmt = HashMap<IrLocal, Any>()
        val live = HashSet<Any>()
        val work = ArrayDeque<Any>()

        fun mark(item: Any) {
            if (live.add(item)) work.addLast(item)
        }

        for (b in reach) {
            for (stmt in b.stmts) {
                if (stmt is IrStmt.LocalAssign) defStmt[stmt.local] = stmt
                if (isRoot(stmt)) mark(stmt)
            }
            for (phi in b.phis) defStmt[phi.target] = phi
            mark(b.terminator!!)
        }
        while (work.isNotEmpty()) {
            val item = work.removeFirst()
            for (v in usesOf(item)) {
                defStmt[v]?.let { mark(it) }
            }
        }

        for (b in reach) {
            b.stmts.retainAll { it !is IrStmt.LocalAssign || it in live }
            b.phis.retainAll { it in live }
            b.succ.retainAll { it in reach }
            b.pred.retainAll { it in reach }
        }
        cfg.blocks.retainAll { it in reach }
    }

    private fun reachableBlocks(): Set<Block> {
        val reach = HashSet<Block>()
        val work = ArrayDeque<Block>()
        reach += cfg.entry
        work += cfg.entry
        while (work.isNotEmpty()) {
            val b = work.removeFirst()
            for (s in b.succ) {
                if (reach.add(s)) work += s
            }
        }
        return reach
    }

    /** 根语句：副作用/可抛异常/终结（含 Try 前 flush 拷贝）。 */
    private fun isRoot(stmt: IrStmt): Boolean = when (stmt) {
        is IrStmt.LocalAssign -> stmt.local in originalLocals || !stmt.value.isDiscardable()
        is IrStmt.Try -> true
        is IrStmt.Call, is IrStmt.New, is IrStmt.Eval, is IrStmt.FieldAccess,
        is IrStmt.Monitor, is IrStmt.Throw, is IrStmt.Await, is IrStmt.Return,
        is IrStmt.Branch, is IrStmt.Goto -> true
        else -> false
    }

    private fun usesOf(item: Any): Set<IrLocal> {
        val out = HashSet<IrLocal>()
        when (item) {
            is IrStmt.LocalAssign -> readLocals(item.value, out)
            is Phi -> item.incoming.forEach { (p, e) -> if (e is IrExpr.LocalRead) out += e.local }
            is IrStmt -> statementReads(item, out)
        }
        return out
    }

    // ── 语句级读取重写 ───────────────────────────────────────────────────────

    private fun mapReadsInStatement(stmt: IrStmt, f: (IrLocal) -> IrLocal): IrStmt = when (stmt) {
        is IrStmt.LocalAssign -> stmt.copy(value = mapReads(stmt.value, f))
        is IrStmt.Call -> stmt.copy(
            receiver = stmt.receiver?.let { mapReads(it, f) },
            args = stmt.args.map { mapReads(it, f) },
        )
        is IrStmt.New -> stmt.copy(args = stmt.args.map { mapReads(it, f) })
        is IrStmt.Eval -> stmt.copy(expr = mapReads(stmt.expr, f))
        is IrStmt.FieldAccess -> stmt.copy(
            receiver = stmt.receiver?.let { mapReads(it, f) },
            value = stmt.value?.let { mapReads(it, f) },
        )
        is IrStmt.Return -> stmt.copy(value = stmt.value?.let { mapReads(it, f) })
        is IrStmt.Throw -> stmt.copy(value = mapReads(stmt.value, f))
        is IrStmt.Monitor -> stmt.copy(expr = mapReads(stmt.expr, f))
        is IrStmt.Await -> stmt.copy(target = mapReads(stmt.target, f))
        else -> stmt
    }

    private fun mapReadsInTerminator(t: IrStmt, f: (IrLocal) -> IrLocal): IrStmt = when (t) {
        is IrStmt.Branch -> t.copy(cond = mapReads(t.cond, f))
        is IrStmt.Return -> t.copy(value = t.value?.let { mapReads(it, f) })
        is IrStmt.Throw -> t.copy(value = mapReads(t.value, f))
        else -> t
    }
}
