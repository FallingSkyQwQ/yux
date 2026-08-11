package yux.compiler.optimizer.ssa

import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType

/**
 * 稀疏条件常量传播（SCCP，Wegman-Zadeck；不动点版本）：
 *
 * 在 SSA 形式上进行全局常量传播——跨越块/φ/循环，并在分支条件成为常量时把
 * 不可达边删除。格子 [Cell]：`Undef < Const < Over`。
 *
 * - 语句按块内顺序求值；纯表达式的值进入格子；副作用/不可折叠表达式置 `Over`；
 * - φ 的值为可达前驱到达版本的 meet；
 * - 分支条件为常量时仅推进被选边（死边在 [apply] 阶段从 CFG 移除）。
 *
 * 折叠语义与 BasicOpt 保持一致（除零不折叠保留运行时语义、Long 原类型比较、
 * String 拼接、Byte 取负回绕等）。
 */
internal class Sccp(private val cfg: Cfg) {

    sealed interface Cell {
        data object Undef : Cell
        data class Const(val value: Any?) : Cell
        data object Over : Cell
    }

    private val cells = HashMap<IrLocal, Cell>()
    private val reachable = HashSet<Block>()
    private val labelToBlock = HashMap<yux.compiler.ir.IrLabel, Block>().also { m ->
        cfg.blocks.forEach { b -> b.label?.let { m[it] = b } }
    }

    /** 常量传播固定点。 */
    fun run() {
        reachable += cfg.entry
        var changed = true
        var rounds = 0
        while (changed && rounds < 200) {
            changed = false
            rounds++
            for (b in cfg.blocks) {
                if (b !in reachable) continue
                for (phi in b.phis) {
                    val v = meetIncoming(phi)
                    if (v != Cell.Undef) changed = update(phi.target, v) || changed
                }
                for (stmt in b.stmts) {
                    when (stmt) {
                        is IrStmt.LocalAssign -> {
                            val v = eval(stmt.value)
                            if (v != Cell.Undef) {
                                // 数值赋值存在目标类型收窄/加宽（`fl:Float = 1` 存入 1.0f）：
                                // 格子值须按目标类型归一，否则替代读取会改变类型语义
                                changed = update(stmt.local, coerce(v, stmt.local.type)) || changed
                            }
                        }
                        is IrStmt.Await -> changed = update(stmt.local, Cell.Over) || changed
                        is IrStmt.Try ->
                            tryDefs(stmt).forEach { changed = update(it, Cell.Over) || changed }
                        else -> Unit
                    }
                }
                when (val t = b.terminator) {
                    is IrStmt.Branch -> {
                        val v = eval(t.cond)
                        when {
                            v == Cell.Const(true) -> changed = markReachable(labelToBlock[t.then]!!) || changed
                            v == Cell.Const(false) -> changed = markReachable(labelToBlock[t.elseLabel]!!) || changed
                            else -> {
                                changed = markReachable(labelToBlock[t.then]!!) || changed
                                changed = markReachable(labelToBlock[t.elseLabel]!!) || changed
                            }
                        }
                    }
                    is IrStmt.Goto -> changed = markReachable(labelToBlock[t.label]!!) || changed
                    is IrStmt.Nop -> b.succ.firstOrNull()?.let { changed = markReachable(it) || changed }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 应用阶段：把常量结果落盘到语句、常量分支折叠为 Goto 并移除死边、剔除
     * 死前驱的 φ 入边。仅处理可达块。
     */
    fun apply() {
        for (b in cfg.blocks) {
            if (b !in reachable) continue
            for (i in b.stmts.indices) {
                when (val stmt = b.stmts[i]) {
                    is IrStmt.LocalAssign -> {
                        val c = cells[stmt.local]
                        if (c is Cell.Const) {
                            b.stmts[i] = stmt.copy(value = IrExpr.Const(c.value))
                        } else {
                            b.stmts[i] = stmt.copy(value = substitute(stmt.value))
                        }
                    }
                    is IrStmt.Call -> b.stmts[i] = stmt.copy(
                        receiver = stmt.receiver?.let { substitute(it) },
                        args = stmt.args.map { substitute(it) },
                    )
                    is IrStmt.New -> b.stmts[i] = stmt.copy(args = stmt.args.map { substitute(it) })
                    is IrStmt.Eval -> b.stmts[i] = stmt.copy(expr = substitute(stmt.expr))
                    is IrStmt.FieldAccess -> b.stmts[i] = stmt.copy(
                        receiver = stmt.receiver?.let { substitute(it) },
                        value = stmt.value?.let { substitute(it) },
                    )
                    is IrStmt.Return -> b.stmts[i] = stmt.copy(value = stmt.value?.let { substitute(it) })
                    is IrStmt.Throw -> b.stmts[i] = stmt.copy(value = substitute(stmt.value))
                    is IrStmt.Monitor -> b.stmts[i] = stmt.copy(expr = substitute(stmt.expr))
                    else -> Unit
                }
            }
            // 终结语句：常量分支折叠为 Goto 并移除死边；其余做常量替换
            when (val t = b.terminator) {
                is IrStmt.Branch -> {
                    val v = eval(t.cond)
                    val taken = when (v) {
                        Cell.Const(true) -> t.then
                        Cell.Const(false) -> t.elseLabel
                        else -> null
                    }
                    if (taken != null) {
                        val dead = if (taken == t.then) t.elseLabel else t.then
                        val deadBlock = labelToBlock[dead]
                        val takenBlock = labelToBlock[taken]
                        if (deadBlock != null && deadBlock !== takenBlock) {
                            b.succ.remove(deadBlock)
                            deadBlock.pred.remove(b)
                        }
                        b.terminator = IrStmt.Goto(taken, t.line)
                    } else {
                        b.terminator = t.copy(cond = substitute(t.cond))
                    }
                }
                is IrStmt.Return -> b.terminator = t.copy(value = t.value?.let { substitute(it) })
                is IrStmt.Throw -> b.terminator = t.copy(value = substitute(t.value))
                else -> Unit
            }
            b.phis.forEach { phi ->
                phi.incoming.retainAll { (p, _) -> p in reachable }
            }
        }
    }

    // ── 格子操作 ─────────────────────────────────────────────────────────────

    private fun update(local: IrLocal, v: Cell): Boolean {
        val old = cells[local] ?: Cell.Undef
        val merged = meet(old, v)
        if (merged == old) return false
        cells[local] = merged
        return true
    }

    private fun meet(a: Cell, b: Cell): Cell = when {
        a == Cell.Undef -> b
        b == Cell.Undef -> a
        a == Cell.Over || b == Cell.Over -> Cell.Over
        a is Cell.Const && b is Cell.Const -> if (a.value == b.value) a else Cell.Over
        else -> Cell.Over
    }

    private fun meetIncoming(phi: Phi): Cell {
        var result: Cell? = null
        for ((p, e) in phi.incoming) {
            if (p !in reachable) continue
            val c = cells[(e as? IrExpr.LocalRead)?.local] ?: Cell.Undef
            if (c == Cell.Undef) return Cell.Undef
            result = if (result == null) c else meet(result, c)
            if (result == Cell.Over) return Cell.Over
        }
        return result ?: Cell.Undef
    }

    private fun markReachable(b: Block): Boolean = reachable.add(b)

    /** 常量按目标类型归一（数值目标类型 → toInt/toLong/toFloat/toDouble/toByte）。 */
    private fun coerce(cell: Cell, type: IrType): Cell {
        if (cell !is Cell.Const) return cell
        val v = cell.value
        if (v !is Number) return cell
        return when ((type.nonNull() as? IrType.Basic)?.name) {
            "Int" -> Cell.Const(v.toInt())
            "Long" -> Cell.Const(v.toLong())
            "Float" -> Cell.Const(v.toFloat())
            "Double" -> Cell.Const(v.toDouble())
            "Byte" -> Cell.Const(v.toByte())
            else -> cell
        }
    }

    // ── 表达式求值 ───────────────────────────────────────────────────────────

    private fun eval(e: IrExpr): Cell = when (e) {
        is IrExpr.Const -> Cell.Const(e.value)
        is IrExpr.LocalRead -> cells[e.local] ?: Cell.Undef
        is IrExpr.Arith -> {
            val l = eval(e.l)
            val r = eval(e.r)
            if (l == Cell.Undef || r == Cell.Undef) Cell.Undef
            else if (l == Cell.Over || r == Cell.Over) Cell.Over
            else foldArith(e.op, (l as Cell.Const).value, (r as Cell.Const).value) ?: Cell.Over
        }
        is IrExpr.Compare -> {
            val l = eval(e.l)
            val r = eval(e.r)
            if (l == Cell.Undef || r == Cell.Undef) Cell.Undef
            else if (l == Cell.Over || r == Cell.Over) Cell.Over
            else foldCompare(e.op, (l as Cell.Const).value, (r as Cell.Const).value) ?: Cell.Over
        }
        is IrExpr.Not -> {
            val v = eval(e.operand)
            if (v == Cell.Undef) Cell.Undef
            else if (v == Cell.Over) Cell.Over
            else Cell.Const(!((v as Cell.Const).value as Boolean))
        }
        is IrExpr.Neg -> {
            val v = eval(e.operand)
            if (v == Cell.Undef) Cell.Undef
            else if (v == Cell.Over) Cell.Over
            else negate((v as Cell.Const).value)?.let { Cell.Const(it) } ?: Cell.Over
        }
        is IrExpr.Convert -> {
            val v = eval(e.expr)
            if (v == Cell.Undef) Cell.Undef
            else if (v == Cell.Over) Cell.Over
            else foldConvert((v as Cell.Const).value, e.to)
        }
        else -> Cell.Over // Invoke/New/FieldRead/StringTemplate/FnInvoke/Lambda/IsType/NullGuard/This/ArrayLoad
    }

    // ── 常量替换（apply）────────────────────────────────────────────────────

    private fun substitute(e: IrExpr): IrExpr = when (e) {
        is IrExpr.LocalRead -> {
            val c = cells[e.local]
            if (c is Cell.Const) IrExpr.Const(c.value) else e
        }
        is IrExpr.FieldRead ->
            e.receiver?.let { IrExpr.FieldRead(substitute(it), e.field) } ?: e
        is IrExpr.New -> IrExpr.New(e.type, e.args.map { substitute(it) })
        is IrExpr.ArrayLoad -> IrExpr.ArrayLoad(substitute(e.base), substitute(e.index), e.elemType)
        is IrExpr.Arith -> IrExpr.Arith(e.op, substitute(e.l), substitute(e.r))
        is IrExpr.Compare -> IrExpr.Compare(e.op, substitute(e.l), substitute(e.r))
        is IrExpr.Not -> IrExpr.Not(substitute(e.operand))
        is IrExpr.Neg -> IrExpr.Neg(substitute(e.operand))
        is IrExpr.Convert -> IrExpr.Convert(substitute(e.expr), e.to)
        is IrExpr.IsType -> IrExpr.IsType(substitute(e.expr), e.type)
        is IrExpr.Invoke ->
            IrExpr.Invoke(e.target, e.receiver?.let { substitute(it) }, e.args.map { substitute(it) }, e.isSuper)
        is IrExpr.FnInvoke -> IrExpr.FnInvoke(substitute(e.fn), e.args.map { substitute(it) })
        is IrExpr.StringTemplate -> IrExpr.StringTemplate(e.parts.map { substitute(it) })
        is IrExpr.NullGuard -> IrExpr.NullGuard(substitute(e.expr))
        is IrExpr.Lambda -> IrExpr.Lambda(e.target, e.captures.map { substitute(it) })
        else -> e
    }

    // ── 折叠辅助（与 BasicOpt 语义一致）──────────────────────────────────────

    private fun foldArith(op: ArithOp, lv: Any?, rv: Any?): Cell.Const? {
        if (lv is String && rv is String && op == ArithOp.ADD) return Cell.Const(lv + rv)
        if (lv !is Number || rv !is Number) return null
        if (op == ArithOp.DIV || op == ArithOp.MOD) {
            if (isZero(rv)) return null // 除零不折叠，保留运行时语义
        }
        return Cell.Const(compute(op, lv, rv))
    }

    private fun foldCompare(op: CompareOp, lv: Any?, rv: Any?): Cell.Const? {
        if (lv == null && rv == null) {
            return when (op) {
                CompareOp.EQ -> Cell.Const(true)
                CompareOp.NE -> Cell.Const(false)
                else -> null
            }
        }
        if (lv == null || rv == null || lv::class != rv::class) return null
        return when (op) {
            CompareOp.EQ -> Cell.Const(lv == rv)
            CompareOp.NE -> Cell.Const(lv != rv)
            else -> compareOrder(op, lv, rv)?.let { Cell.Const(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareOrder(op: CompareOp, l: Any, r: Any): Boolean? {
        if (l is Long && r is Long) {
            return when (op) {
                CompareOp.LT -> l < r
                CompareOp.LE -> l <= r
                CompareOp.GT -> l > r
                CompareOp.GE -> l >= r
                else -> null
            }
        }
        if (l is Number && r is Number) {
            val lv = l.toDouble()
            val rv = r.toDouble()
            return when (op) {
                CompareOp.LT -> lv < rv
                CompareOp.LE -> lv <= rv
                CompareOp.GT -> lv > rv
                CompareOp.GE -> lv >= rv
                else -> null
            }
        }
        if (l is String && r is String) {
            return when (op) {
                CompareOp.LT -> l < r
                CompareOp.LE -> l <= r
                CompareOp.GT -> l > r
                CompareOp.GE -> l >= r
                else -> null
            }
        }
        return null
    }

    private fun foldConvert(v: Any?, to: IrType): Cell {
        if (v !is Number) return Cell.Over
        val converted: Any? = when ((to.nonNull() as? IrType.Basic)?.name) {
            "Int" -> v.toInt()
            "Long" -> v.toLong()
            "Float" -> v.toFloat()
            "Double" -> v.toDouble()
            "Byte" -> v.toByte()
            else -> return Cell.Over
        }
        return Cell.Const(converted)
    }

    private fun negate(v: Any?): Any? = when (v) {
        is Int -> -v
        is Long -> -v
        is Float -> -v
        is Double -> -v
        is Byte -> (-v).toByte()
        else -> null
    }

    private fun isZero(v: Any?): Boolean = when (v) {
        is Int -> v == 0
        is Long -> v == 0L
        is Float -> v == 0.0f
        is Double -> v == 0.0
        is Byte -> v == 0.toByte()
        else -> false
    }

    private fun compute(op: ArithOp, l: Number, r: Number): Any = when (op) {
        ArithOp.ADD -> add(l, r)
        ArithOp.SUB -> sub(l, r)
        ArithOp.MUL -> mul(l, r)
        ArithOp.DIV -> div(l, r)
        ArithOp.MOD -> mod(l, r)
    }

    private fun add(l: Number, r: Number): Any = when {
        l is Double || r is Double -> l.toDouble() + r.toDouble()
        l is Float || r is Float -> l.toFloat() + r.toFloat()
        l is Long || r is Long -> l.toLong() + r.toLong()
        else -> l.toInt() + r.toInt()
    }

    private fun sub(l: Number, r: Number): Any = when {
        l is Double || r is Double -> l.toDouble() - r.toDouble()
        l is Float || r is Float -> l.toFloat() - r.toFloat()
        l is Long || r is Long -> l.toLong() - r.toLong()
        else -> l.toInt() - r.toInt()
    }

    private fun mul(l: Number, r: Number): Any = when {
        l is Double || r is Double -> l.toDouble() * r.toDouble()
        l is Float || r is Float -> l.toFloat() * r.toFloat()
        l is Long || r is Long -> l.toLong() * r.toLong()
        else -> l.toInt() * r.toInt()
    }

    private fun div(l: Number, r: Number): Any = when {
        l is Double || r is Double -> l.toDouble() / r.toDouble()
        l is Float || r is Float -> l.toFloat() / r.toFloat()
        l is Long || r is Long -> l.toLong() / r.toLong()
        else -> l.toInt() / r.toInt()
    }

    private fun mod(l: Number, r: Number): Any = when {
        l is Double || r is Double -> l.toDouble() % r.toDouble()
        l is Float || r is Float -> l.toFloat() % r.toFloat()
        l is Long || r is Long -> l.toLong() % r.toLong()
        else -> l.toInt() % r.toInt()
    }
}
