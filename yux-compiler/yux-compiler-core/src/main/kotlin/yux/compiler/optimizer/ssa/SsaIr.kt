package yux.compiler.optimizer.ssa

import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrStmt

/**
 * SSA 中间表示（完整 SSA 优化器核心，02-§8.4 的「完整 SSA」落地）：
 *
 * 基于既有线性 IR（Label/Goto/Branch + 结构化 Try）构造控制流图：
 * - [Block]：基本块（[label] 为块起始标签，入口块为 null）；[stmts] 为块体语句，
 *   [terminator] 为终结语句（Goto/Branch/Return/Throw，null = 落空）；
 * - [Phi]：块起始处的 φ 节点（分析期虚拟节点，销毁时按入边落地为拷贝）；
 * - [Cfg]：块集合 + 边（[succ]/[pred]）+ 入口块。
 *
 * 结构化 Try 被建模为**不透明节点**：对闭包外层它只是块内一个语句，其内部
 * 子列表（body/catch/finally）自含控制流，不外泄（见 [tryRegionSelfContained]）；
 * 数据流上按「定义/读取集合」保守处理（见 SsaBuilder 的 try-reset 模型）。
 */
internal class Block(
    var label: IrLabel? = null,
    val stmts: MutableList<IrStmt> = mutableListOf(),
    var terminator: IrStmt? = null,
) {
    val pred = mutableListOf<Block>()
    val succ = mutableListOf<Block>()
    val phis = mutableListOf<Phi>()

    /** 终结（Return/Throw）：无后继。 */
    val exitsMethod: Boolean get() = terminator is IrStmt.Return || terminator is IrStmt.Throw
}

/** φ 节点：块起始处合并 [origin] 变量在各位前驱块的到达版本；[incoming] 与 [pred] 对应。 */
internal class Phi(
    val target: IrLocal,
    val origin: IrLocal,
    val incoming: MutableList<Pair<Block, IrExpr>> = mutableListOf(),
)

/** 控制流图。 */
internal class Cfg(
    val blocks: MutableList<Block>,
    val entry: Block,
)

/** 由线性语句列表构建 CFG（保持原顺序；落空块 terminator 用 Nop 占位）。 */
internal class CfgBuilder {
    fun build(stmts: List<IrStmt>): Cfg {
        val blocks = mutableListOf<Block>()
        val labelToBlock = HashMap<IrLabel, Block>()
        var cur = Block()
        blocks += cur

        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.Label -> {
                    cur.terminator = cur.terminator ?: IrStmt.Nop
                    cur = Block(label = stmt.label)
                    blocks += cur
                    labelToBlock[stmt.label] = cur
                }
                // 终结语句不进 [stmts]（仅存 [terminator]，重写时单一事实源）
                is IrStmt.Goto, is IrStmt.Branch, is IrStmt.Return, is IrStmt.Throw -> {
                    cur.terminator = stmt
                    cur = Block()
                    blocks += cur
                }
                else -> cur.stmts += stmt
            }
        }
        cur.terminator = cur.terminator ?: IrStmt.Nop

        for (i in blocks.indices) {
            val b = blocks[i]
            val next = blocks.getOrNull(i + 1)
            val targets: List<Block> = when (val t = b.terminator) {
                is IrStmt.Nop -> if (next != null) listOf(next) else emptyList()
                is IrStmt.Goto -> listOf(labelToBlock[t.label] ?: error("SSA: Goto 目标标签未定义: ${t.label.name}"))
                is IrStmt.Branch -> {
                    val thenB = labelToBlock[t.then] ?: error("SSA: 分支目标标签未定义: ${t.then.name}")
                    val elseB = labelToBlock[t.elseLabel] ?: error("SSA: 分支目标标签未定义: ${t.elseLabel.name}")
                    if (elseB === thenB) listOf(thenB) else listOf(thenB, elseB)
                }
                else -> emptyList()
            }
            for (t in targets) {
                b.succ += t
                t.pred += b
            }
        }
        return Cfg(blocks, blocks.first())
    }
}

// ── 支配关系 ─────────────────────────────────────────────────────────────────

/** 从入口出发的 DFS 先序编号（Cooper 交点运算需要：支配者先序号必小于被支配者）。 */
internal fun preorderNumbers(cfg: Cfg): Map<Block, Int> {
    val pre = HashMap<Block, Int>()
    var n = 0
    fun dfs(b: Block) {
        pre[b] = n++
        for (s in b.succ) if (s !in pre) dfs(s)
    }
    dfs(cfg.entry)
    return pre
}

/**
 * 计算各块的直接支配者（Cooper-Harvey-Kennedy 迭代不动点算法）：
 * 交点运算基于先序编号沿 idom 链上溯——被支配者的先序号必大于支配者，
 * 故「攀爬更深的指针」必然收敛到最近的共同支配者。
 */
internal fun computeImmediateDominators(cfg: Cfg): Map<Block, Block> {
    val pre = preorderNumbers(cfg)
    val idom = HashMap<Block, Block>()
    idom[cfg.entry] = cfg.entry
    val ordered = cfg.blocks.filter { it !== cfg.entry && it in pre }.sortedBy { pre[it] }
    var changed = true
    while (changed) {
        changed = false
        for (b in ordered) {
            val preds = b.pred.filter { idom.containsKey(it) }
            if (preds.isEmpty()) continue
            var newIdom = preds.first()
            for (p in preds.drop(1)) {
                newIdom = intersect(p, newIdom, idom, pre)
            }
            if (idom[b] != newIdom) {
                idom[b] = newIdom
                changed = true
            }
        }
    }
    return idom
}

/** 交点运算：先序号大的（更深）沿 idom 链上溯，直至两指针汇合。 */
private fun intersect(b1: Block, b2: Block, idom: Map<Block, Block>, pre: Map<Block, Int>): Block {
    var f1 = b1
    var f2 = b2
    while (f1 !== f2) {
        if ((pre[f1] ?: Int.MAX_VALUE) > (pre[f2] ?: Int.MAX_VALUE)) {
            f1 = idom[f1] ?: break
        } else {
            f2 = idom[f2] ?: break
        }
    }
    return f1
}

/** 计算各块的支配边界 DF（LinkedHashSet 保持确定性插入序）。 */
internal fun computeDominanceFrontiers(cfg: Cfg, idom: Map<Block, Block>): Map<Block, MutableSet<Block>> {
    val df = HashMap<Block, MutableSet<Block>>()
    cfg.blocks.forEach { df[it] = LinkedHashSet() }
    for (b in cfg.blocks) {
        if (b.pred.size < 2) continue
        for (p in b.pred) {
            var runner = p
            while (runner !== idom[b]) {
                df[runner]!!.add(b)
                runner = idom[runner] ?: break
            }
        }
    }
    return df
}

/** 支配树子节点（重命名递归遍历用）。 */
internal fun dominatorChildren(cfg: Cfg, idom: Map<Block, Block>): Map<Block, List<Block>> {
    val children = HashMap<Block, MutableList<Block>>()
    cfg.blocks.forEach { children[it] = mutableListOf() }
    for (b in cfg.blocks) {
        if (b === cfg.entry) continue
        idom[b]?.let { children[it]!!.add(b) }
    }
    return children
}

// ── 局部引用收集（use/def/try 安全）──────────────────────────────────────────

/** 收集表达式中读取的局部（含嵌套；Lambda 捕获、StringTemplate 段等）。 */
internal fun readLocals(e: IrExpr?, out: MutableSet<IrLocal>) {
    if (e == null) return
    when (e) {
        is IrExpr.LocalRead -> out += e.local
        is IrExpr.FieldRead -> readLocals(e.receiver, out)
        is IrExpr.New -> e.args.forEach { readLocals(it, out) }
        is IrExpr.ArrayLoad -> { readLocals(e.base, out); readLocals(e.index, out) }
        is IrExpr.Arith -> { readLocals(e.l, out); readLocals(e.r, out) }
        is IrExpr.Compare -> { readLocals(e.l, out); readLocals(e.r, out) }
        is IrExpr.Not -> readLocals(e.operand, out)
        is IrExpr.Neg -> readLocals(e.operand, out)
        is IrExpr.Convert -> readLocals(e.expr, out)
        is IrExpr.IsType -> readLocals(e.expr, out)
        is IrExpr.Invoke -> { readLocals(e.receiver, out); e.args.forEach { readLocals(it, out) } }
        is IrExpr.FnInvoke -> { readLocals(e.fn, out); e.args.forEach { readLocals(it, out) } }
        is IrExpr.StringTemplate -> e.parts.forEach { readLocals(it, out) }
        is IrExpr.NullGuard -> readLocals(e.expr, out)
        is IrExpr.Lambda -> e.captures.forEach { readLocals(it, out) }
        else -> Unit
    }
}

/** 表达式读取映射重写：`f(local)` 替换 `LocalRead(local)`（返回新值则重建节点）。 */
internal fun mapReads(e: IrExpr, f: (IrLocal) -> IrLocal): IrExpr = when (e) {
    is IrExpr.LocalRead -> {
        val n = f(e.local)
        if (n !== e.local) IrExpr.LocalRead(n) else e
    }
    is IrExpr.FieldRead ->
        e.receiver?.let { IrExpr.FieldRead(mapReads(it, f), e.field) } ?: e
    is IrExpr.New -> IrExpr.New(e.type, e.args.map { mapReads(it, f) })
    is IrExpr.ArrayLoad -> IrExpr.ArrayLoad(mapReads(e.base, f), mapReads(e.index, f), e.elemType)
    is IrExpr.Arith -> IrExpr.Arith(e.op, mapReads(e.l, f), mapReads(e.r, f))
    is IrExpr.Compare -> IrExpr.Compare(e.op, mapReads(e.l, f), mapReads(e.r, f))
    is IrExpr.Not -> IrExpr.Not(mapReads(e.operand, f))
    is IrExpr.Neg -> IrExpr.Neg(mapReads(e.operand, f))
    is IrExpr.Convert -> IrExpr.Convert(mapReads(e.expr, f), e.to)
    is IrExpr.IsType -> IrExpr.IsType(mapReads(e.expr, f), e.type)
    is IrExpr.Invoke ->
        IrExpr.Invoke(e.target, e.receiver?.let { mapReads(it, f) }, e.args.map { mapReads(it, f) }, e.isSuper)
    is IrExpr.FnInvoke -> IrExpr.FnInvoke(mapReads(e.fn, f), e.args.map { mapReads(it, f) })
    is IrExpr.StringTemplate -> IrExpr.StringTemplate(e.parts.map { mapReads(it, f) })
    is IrExpr.NullGuard -> IrExpr.NullGuard(mapReads(e.expr, f))
    is IrExpr.Lambda -> IrExpr.Lambda(e.target, e.captures.map { mapReads(it, f) })
    else -> e
}

/** 语句的 use 集合（含 Try 内部子列表，保守计数以保证 try 前 flush 拷贝存活）。 */
internal fun statementReads(stmt: IrStmt, out: MutableSet<IrLocal>) {
    when (stmt) {
        is IrStmt.LocalAssign -> readLocals(stmt.value, out)
        is IrStmt.Call -> { readLocals(stmt.receiver, out); stmt.args.forEach { readLocals(it, out) } }
        is IrStmt.New -> stmt.args.forEach { readLocals(it, out) }
        is IrStmt.Eval -> readLocals(stmt.expr, out)
        is IrStmt.FieldAccess -> { readLocals(stmt.receiver, out); readLocals(stmt.value, out) }
        is IrStmt.Branch -> readLocals(stmt.cond, out)
        is IrStmt.Return -> readLocals(stmt.value, out)
        is IrStmt.Throw -> readLocals(stmt.value, out)
        is IrStmt.Monitor -> readLocals(stmt.expr, out)
        is IrStmt.Await -> readLocals(stmt.target, out)
        is IrStmt.Try -> {
            stmt.body.forEach { statementReads(it, out) }
            stmt.catches.forEach { it.body.forEach { c -> statementReads(c, out) } }
            stmt.finallyBody?.forEach { statementReads(it, out) }
        }
        else -> Unit
    }
}

/** Try 内读取/定义集合（递归嵌套 Try；SsaBuilder/Sccp 共用）。 */
internal fun tryRefs(t: IrStmt.Try): Pair<Set<IrLocal>, Set<IrLocal>> {
    val uses = HashSet<IrLocal>()
    val defs = HashSet<IrLocal>()
    fun walk(s: IrStmt) {
        when (s) {
            is IrStmt.LocalAssign -> { defs += s.local; readLocals(s.value, uses) }
            is IrStmt.Await -> { defs += s.local; readLocals(s.target, uses) }
            is IrStmt.Try -> {
                s.body.forEach { walk(it) }
                s.catches.forEach { it.body.forEach { c -> walk(c) } }
                s.finallyBody?.forEach { walk(it) }
            }
            else -> statementReads(s, uses)
        }
    }
    t.body.forEach { walk(it) }
    t.catches.forEach { it.body.forEach { c -> walk(c) } }
    t.finallyBody?.forEach { walk(it) }
    return uses to defs
}

/** Try 内定义集合（闭包外层视角：Try 会「定义」这些局部）。 */
internal fun tryDefs(t: IrStmt.Try): Set<IrLocal> = tryRefs(t).second

/**
 * Try 内子列表的自含性检查：每个子列表（body/catch/finally）内**任意深度**的
 * 跳转目标都必须定义在**同一子列表内**（含嵌套 Try 内部的标签）。否则该 Try
 * 会向闭包外层逃逸（如 break/continue 跨 try），SSA 建模不可靠，调用方须放弃
 * 该方法（回退 BasicOpt）。
 */
internal fun tryRegionSelfContained(stmt: IrStmt.Try): Boolean =
    stmt.body.let { selfContained(it) } &&
        stmt.catches.all { selfContained(it.body) } &&
        (stmt.finallyBody == null || selfContained(stmt.finallyBody))

private fun selfContained(stmts: List<IrStmt>): Boolean {
    val labels = allLabels(stmts)
    return allJumpTargets(stmts).all { it in labels }
}

/** 任意深度的标签集合（递归进入嵌套 Try）。 */
private fun allLabels(stmts: List<IrStmt>): Set<IrLabel> {
    val out = HashSet<IrLabel>()
    fun walk(s: IrStmt) {
        when (s) {
            is IrStmt.Label -> out += s.label
            is IrStmt.Try -> {
                s.body.forEach { walk(it) }
                s.catches.forEach { it.body.forEach { c -> walk(c) } }
                s.finallyBody?.forEach { walk(it) }
            }
            else -> Unit
        }
    }
    stmts.forEach { walk(it) }
    return out
}

/** 任意深度的跳转目标（递归进入嵌套 Try）。 */
private fun allJumpTargets(stmts: List<IrStmt>): Set<IrLabel> {
    val out = HashSet<IrLabel>()
    fun walk(s: IrStmt) {
        when (s) {
            is IrStmt.Goto -> out += s.label
            is IrStmt.Branch -> { out += s.then; out += s.elseLabel }
            is IrStmt.Try -> {
                s.body.forEach { walk(it) }
                s.catches.forEach { it.body.forEach { c -> walk(c) } }
                s.finallyBody?.forEach { walk(it) }
            }
            else -> Unit
        }
    }
    stmts.forEach { walk(it) }
    return out
}
