package yux.compiler.optimizer.ssa

import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrStmt

/**
 * SSA 构造（φ 插入 + 重命名，Cytron 算法）：
 *
 * - **φ 插入**：对定义点 ≥2 的局部，在支配边界插入 φ 节点；
 * - **重命名**：按支配树先序递归，块内语句顺序扫描；每次赋值产生新的版本局部
 *   （[IrLocal] 实例），后续读取重写到当前版本；
 * - **结构化 Try（闭包外层视角）**：Try 为不透明语句。重命名到 Try 时，对
 *   Try 内被**读取或定义**的局部，先在此前发射「flush 拷贝」（把外层当前版本
 *   复制进原局部寄存器，保证 Try 内代码读到正确的值）；随后 Try 内被定义的
 *   局部恢复为「原局部」作为当前版本（其新值由 Try 内代码写入原寄存器）。
 *
 * 版本局部分配新 index（从方法最大 index 起），保证后端槽位分配互不冲突。
 */
internal class SsaBuilder(
    private val cfg: Cfg,
    params: List<IrLocal>,
    firstVersionIndex: Int,
) {
    private val current = HashMap<IrLocal, IrLocal>()
    private var nextIndex = firstVersionIndex

    init {
        params.forEach { current[it] = it }
    }

    /** 构造 SSA 形式：φ 插入 + 重命名。 */
    fun build() {
        insertPhis()
        val idom = computeImmediateDominators(cfg)
        val children = dominatorChildren(cfg, idom)
        renameBlock(cfg.entry, children)
    }

    // ── φ 插入 ──────────────────────────────────────────────────────────────

    private fun insertPhis() {
        // LinkedHashMap/LinkedHashSet 保证 φ 创建顺序确定（版本 index 与 golden 快照稳定）
        val defsites = LinkedHashMap<IrLocal, MutableSet<Block>>()
        cfg.entry.also { entry ->
            current.keys.forEach { defsites.getOrPut(it) { LinkedHashSet() }.add(entry) }
        }
        for (b in cfg.blocks) {
            for (stmt in b.stmts) {
                when (stmt) {
                    is IrStmt.LocalAssign ->
                        defsites.getOrPut(stmt.local) { LinkedHashSet() }.add(b)
                    is IrStmt.Await ->
                        defsites.getOrPut(stmt.local) { LinkedHashSet() }.add(b)
                    is IrStmt.Try ->
                        tryDefs(stmt).forEach { defsites.getOrPut(it) { LinkedHashSet() }.add(b) }
                    else -> Unit
                }
            }
        }
        val df = computeDominanceFrontiers(cfg, computeImmediateDominators(cfg))
        for ((local, sites) in defsites.entries.sortedBy { it.key.index }) {
            if (sites.size < 2) continue
            val work = sites.toMutableList()
            val hasPhi = HashMap<Block, Boolean>()
            while (work.isNotEmpty()) {
                val b = work.removeAt(work.size - 1)
                for (d in df[b]!!) {
                    if (hasPhi[d] == true) continue
                    hasPhi[d] = true
                    d.phis += Phi(newVersion(local), local)
                    if (d !in sites) {
                        sites.add(d)
                        work += d
                    }
                }
            }
        }
    }

    // ── 重命名 ──────────────────────────────────────────────────────────────

    private fun renameBlock(b: Block, children: Map<Block, List<Block>>) {
        val saved = HashMap(current)
        for (phi in b.phis) {
            current[phi.origin] = phi.target
        }

        var i = 0
        while (i < b.stmts.size) {
            val stmt = b.stmts[i]
            when (stmt) {
                is IrStmt.LocalAssign -> {
                    b.stmts[i] = stmt.copy(value = rewriteExpr(stmt.value), local = newVersion(stmt.local))
                    i++
                }
                is IrStmt.Await -> {
                    b.stmts[i] = stmt.copy(target = rewriteExpr(stmt.target), local = newVersion(stmt.local))
                    i++
                }
                is IrStmt.Try -> {
                    val (uses, defs) = tryRefs(stmt)
                    val flushTargets = (uses + defs).sortedBy { it.index }
                    for (l in flushTargets) {
                        val cur = current[l]
                        if (cur != null && cur !== l) {
                            b.stmts.add(i, IrStmt.LocalAssign(l, IrExpr.LocalRead(cur)))
                            i++
                        }
                    }
                    defs.forEach { current[it] = it }
                    i++
                }
                is IrStmt.Call -> {
                    b.stmts[i] = stmt.copy(
                        receiver = stmt.receiver?.let { rewriteExpr(it) },
                        args = stmt.args.map { rewriteExpr(it) },
                    )
                    i++
                }
                is IrStmt.New -> {
                    b.stmts[i] = stmt.copy(args = stmt.args.map { rewriteExpr(it) })
                    i++
                }
                is IrStmt.Eval -> {
                    b.stmts[i] = stmt.copy(expr = rewriteExpr(stmt.expr))
                    i++
                }
                is IrStmt.FieldAccess -> {
                    b.stmts[i] = stmt.copy(
                        receiver = stmt.receiver?.let { rewriteExpr(it) },
                        value = stmt.value?.let { rewriteExpr(it) },
                    )
                    i++
                }
                is IrStmt.Return -> {
                    b.stmts[i] = stmt.copy(value = stmt.value?.let { rewriteExpr(it) })
                    i++
                }
                is IrStmt.Throw -> {
                    b.stmts[i] = stmt.copy(value = rewriteExpr(stmt.value))
                    i++
                }
                is IrStmt.Monitor -> {
                    b.stmts[i] = stmt.copy(expr = rewriteExpr(stmt.expr))
                    i++
                }
                else -> i++
            }
        }

        b.terminator = when (val t = b.terminator) {
            is IrStmt.Branch -> t.copy(cond = rewriteExpr(t.cond))
            is IrStmt.Return -> t.copy(value = t.value?.let { rewriteExpr(it) })
            is IrStmt.Throw -> t.copy(value = rewriteExpr(t.value))
            else -> t
        }

        for (s in b.succ) {
            for (phi in s.phis) {
                val v = current[phi.origin] ?: continue
                phi.incoming += b to IrExpr.LocalRead(v)
            }
        }

        for (c in children[b] ?: emptyList()) {
            renameBlock(c, children)
        }

        current.clear()
        current.putAll(saved)
    }

    /** 表达式读取重写到当前版本（未登记局部保持原样）。 */
    private fun rewriteExpr(e: IrExpr): IrExpr = mapReads(e) { l -> current[l] ?: l }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private fun newVersion(origin: IrLocal): IrLocal {
        val v = IrLocal(origin.name, origin.type, nextIndex++)
        current[origin] = v
        return v
    }
}
