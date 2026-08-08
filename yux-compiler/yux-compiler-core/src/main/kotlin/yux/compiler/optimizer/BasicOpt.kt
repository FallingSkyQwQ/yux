package yux.compiler.optimizer

import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType

/**
 * 最小优化器（T-M4-5 / 02-§8.4，默认开启）：
 *
 * 1. **常量折叠**：`Arith/Compare/Not/Neg/Convert/Branch` 作用于常量时求值为新常量
 *    （除零不折叠，保留运行时语义）；
 * 2. **死代码消除**：终结语句（Return/Throw/Goto）后的不可达语句移除；纯 RHS 且
 *    未被读取的局部赋值移除；
 * 3. **冗余跳转消除**：`Goto(L)` 紧随 `Label(L)` 时删除；`Branch(c, L, L)` 删除；
 * 4. **空守卫折叠**（02-§8.4）：接收者静态非空时去守卫；接收者静态为 null 时
 *    折叠为类型默认值。
 *
 * 在 [IrModule] 方法体上**原地**操作，重复迭代至不动点（最多 3 轮）。
 */
object BasicOpt {

    /** 对整个模块执行最小优化（原地）。 */
    fun optimize(module: IrModule) {
        for (cls in module.classes) {
            for (method in cls.methods) {
                // 迭代至不动点（折叠→DCE→折叠 可互相激活；最多 3 轮）
                repeat(3) {
                    if (!optimizeBody(method.body)) return@repeat
                }
            }
        }
    }

    private fun optimizeBody(body: MutableList<IrStmt>): Boolean {
        var changed = false
        changed = foldStatements(body) || changed
        changed = removeUnreachable(body) || changed
        changed = removeUnusedAssigns(body) || changed
        changed = removeRedundantJumps(body) || changed
        return changed
    }

    // ── 常量折叠 + 空守卫折叠（语句级重写）────────────────────────────────────

    private fun foldStatements(body: MutableList<IrStmt>): Boolean {
        var changed = false
        val out = ArrayList<IrStmt>(body.size)
        for (stmt in body) {
            out += foldStmt(stmt).also { if (it != stmt) changed = true }
        }
        if (changed) {
            body.clear()
            body.addAll(out)
        }
        return changed
    }

    private fun foldStmt(stmt: IrStmt): IrStmt = when (stmt) {
        is IrStmt.LocalAssign -> IrStmt.LocalAssign(stmt.local, foldExpr(stmt.value))
        is IrStmt.Call -> IrStmt.Call(stmt.callee, stmt.receiver, stmt.args.map(::foldExpr), stmt.ret)
        is IrStmt.New -> IrStmt.New(stmt.type, stmt.args.map(::foldExpr))
        is IrStmt.FieldAccess -> stmt.value?.let { IrStmt.FieldAccess(stmt.receiver, stmt.field, stmt.write, foldExpr(it)) } ?: stmt
        is IrStmt.Branch -> foldBranch(stmt)
        is IrStmt.Return -> stmt.value?.let { IrStmt.Return(foldExpr(it)) } ?: stmt
        is IrStmt.Throw -> IrStmt.Throw(foldExpr(stmt.value))
        is IrStmt.Monitor -> IrStmt.Monitor(foldExpr(stmt.expr), stmt.enter)
        is IrStmt.Try -> IrStmt.Try(
            stmt.body.map(::foldStmt),
            stmt.catches.map { yux.compiler.ir.IrCatch(it.paramName, it.type, it.body.map(::foldStmt)) },
            stmt.finallyBody?.map(::foldStmt),
        )
        else -> stmt // Label / Goto / Nop
    }

    /** `Branch(Const, L1, L2)` → `Goto(目标)`。 */
    private fun foldBranch(stmt: IrStmt.Branch): IrStmt {
        val cond = foldExpr(stmt.cond) as? IrExpr.Const ?: return IrStmt.Branch(stmt.cond, stmt.then, stmt.elseLabel)
        val value = cond.value as? Boolean ?: return stmt
        return IrStmt.Goto(if (value) stmt.then else stmt.elseLabel)
    }

    private fun foldExpr(expr: IrExpr): IrExpr {
        val folded = when (expr) {
            is IrExpr.Arith -> {
                val l = foldExpr(expr.l)
                val r = foldExpr(expr.r)
                foldArith(expr.op, l, r)
            }
            is IrExpr.Compare -> {
                val l = foldExpr(expr.l)
                val r = foldExpr(expr.r)
                foldCompare(expr.op, l, r)
            }
            is IrExpr.Not -> {
                val operand = foldExpr(expr.operand)
                if (operand is IrExpr.Const && operand.value is Boolean) IrExpr.Const(!operand.value) else IrExpr.Not(operand)
            }
            is IrExpr.Neg -> {
                val operand = foldExpr(expr.operand)
                if (operand is IrExpr.Const && isNumeric(operand.value)) IrExpr.Const(negate(operand.value!!)) else IrExpr.Neg(operand)
            }
            is IrExpr.Convert -> {
                val inner = foldExpr(expr.expr)
                if (inner is IrExpr.Const && inner.value is Number) foldConvert(inner, expr.to) else IrExpr.Convert(inner, expr.to)
            }
            is IrExpr.NullGuard -> foldNullGuard(expr)
            is IrExpr.New -> IrExpr.New(expr.type, expr.args.map(::foldExpr))
            is IrExpr.Invoke -> IrExpr.Invoke(expr.target, expr.receiver?.let(::foldExpr), expr.args.map(::foldExpr))
            is IrExpr.FieldRead -> expr.receiver?.let { IrExpr.FieldRead(foldExpr(it), expr.field) } ?: expr
            is IrExpr.StringTemplate -> IrExpr.StringTemplate(expr.parts.map(::foldExpr))
            is IrExpr.IsType -> IrExpr.IsType(foldExpr(expr.expr), expr.type)
            else -> expr
        }
        return folded
    }

    private fun foldArith(op: ArithOp, l: IrExpr, r: IrExpr): IrExpr {
        if (l !is IrExpr.Const || r !is IrExpr.Const) return IrExpr.Arith(op, l, r)
        val lv = l.value
        val rv = r.value
        if (lv is String && rv is String && op == ArithOp.ADD) return IrExpr.Const(lv + rv)
        if (lv !is Number || rv !is Number) return IrExpr.Arith(op, l, r)
        if (op == ArithOp.DIV || op == ArithOp.MOD) {
            if (isZero(rv)) return IrExpr.Arith(op, l, r) // 除零不折叠，保留运行时语义
            // JVM 上 MIN_VALUE / -1 抛 ArithmeticException：折叠会崩编译期，故不折叠
            if (isMinOverNegOne(op, lv, rv)) return IrExpr.Arith(op, l, r)
        }
        return IrExpr.Const(compute(op, lv, rv))
    }

    /** `Int/Long.MIN_VALUE 除以 -1`：JVM 抛异常（IDIV 特殊情形），折叠会崩编译期。 */
    private fun isMinOverNegOne(op: ArithOp, l: Number, r: Number): Boolean = when {
        l is Int && r is Int -> (op == ArithOp.DIV || op == ArithOp.MOD) && l == Int.MIN_VALUE && r == -1
        l is Long && r is Long -> (op == ArithOp.DIV || op == ArithOp.MOD) && l == Long.MIN_VALUE && r == -1L
        else -> false
    }

    private fun foldCompare(op: CompareOp, l: IrExpr, r: IrExpr): IrExpr {
        if (l !is IrExpr.Const || r !is IrExpr.Const) return IrExpr.Compare(op, l, r)
        val lv = l.value
        val rv = r.value
        if (lv == null && rv == null) {
            // null == null / null != null 可折叠；序比较不可判定
            return when (op) {
                CompareOp.EQ -> IrExpr.Const(true)
                CompareOp.NE -> IrExpr.Const(false)
                else -> IrExpr.Compare(op, l, r)
            }
        }
        if (lv == null || rv == null || lv::class != rv::class) return IrExpr.Compare(op, l, r)
        val result: Boolean = when (op) {
            CompareOp.EQ -> lv == rv
            CompareOp.NE -> lv != rv
            else -> compareOrder(op, lv, rv) ?: return IrExpr.Compare(op, l, r)
        }
        return IrExpr.Const(result)
    }

    /** 数值/字符串序比较；不可比较类型返回 null（不折叠）。 */
    @Suppress("UNCHECKED_CAST")
    private fun compareOrder(op: CompareOp, l: Any, r: Any): Boolean? {
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

    private fun foldConvert(inner: IrExpr.Const, to: IrType): IrExpr {
        val v = inner.value as Number
        val converted: Any? = when ((to.nonNull() as? IrType.Basic)?.name) {
            "Int" -> v.toInt()
            "Long" -> v.toLong()
            "Float" -> v.toFloat()
            "Double" -> v.toDouble()
            "Byte" -> v.toByte()
            else -> return IrExpr.Convert(inner, to)
        }
        return IrExpr.Const(converted)
    }

    /** 空守卫折叠：接收者静态为 null → 类型默认值；静态非空 → 去守卫。 */
    private fun foldNullGuard(expr: IrExpr.NullGuard): IrExpr {
        val inner = foldExpr(expr.expr)
        val receiver = when (inner) {
            is IrExpr.FieldRead -> inner.receiver
            is IrExpr.Invoke -> inner.receiver
            else -> inner
        }
        // 接收者静态为 null → 守卫结果恒为类型默认值（先于可空性判断：
        // Const(null) 的 inferType 为 Nothing，非 Nullable）
        if (receiver is IrExpr.Const && receiver.value == null) {
            return IrExpr.Const(IrType.defaultValue(inner.inferType() ?: IrType.ANY))
        }
        // 接收者静态非空（New / 非空常量 / 非可空类型）→ 去守卫
        if (receiver is IrExpr.New) return inner
        if (receiver is IrExpr.Const) return inner
        val receiverType = receiver?.inferType()
        if (receiverType != null && !receiverType.nullable && receiverType !is IrType.Nothing) return inner
        return IrExpr.NullGuard(inner)
    }

    // ── 死代码消除 ─────────────────────────────────────────────────────────────

    /** 终结语句后的不可达语句移除（Label 保留：可能为跳转目标）。 */
    private fun removeUnreachable(body: MutableList<IrStmt>): Boolean {
        var changed = false
        val out = ArrayList<IrStmt>(body.size)
        var dead = false
        for (stmt in body) {
            when {
                dead && stmt !is IrStmt.Label -> {
                    changed = true
                    continue
                }
                stmt is IrStmt.Label -> {
                    dead = false
                    out += stmt
                }
                stmt is IrStmt.Return || stmt is IrStmt.Throw -> {
                    out += stmt
                    dead = true
                }
                stmt is IrStmt.Goto -> {
                    out += stmt
                    dead = true
                }
                else -> out += stmt
            }
        }
        if (changed) {
            body.clear()
            body.addAll(out)
        }
        return changed
    }

    /** 未读取的纯 RHS 局部赋值移除（副作用表达式保留）。 */
    private fun removeUnusedAssigns(body: MutableList<IrStmt>): Boolean {
        val read = readLocals(body)
        var changed = false
        val out = ArrayList<IrStmt>(body.size)
        for (stmt in body) {
            val keep = when (stmt) {
                is IrStmt.LocalAssign -> {
                    val sideEffect = hasSideEffect(stmt.value)
                    if (!sideEffect && stmt.local !in read) changed = true
                    sideEffect || stmt.local in read
                }
                else -> true
            }
            if (keep) out += stmt
        }
        if (changed) {
            body.clear()
            body.addAll(out)
        }
        return changed
    }

    /** 收集整个方法体中所有被读取的局部（含 Try 子块）。 */
    private fun readLocals(body: List<IrStmt>): Set<yux.compiler.ir.IrLocal> {
        val read = HashSet<yux.compiler.ir.IrLocal>()
        fun walkExpr(e: IrExpr?) {
            if (e == null) return
            when (e) {
                is IrExpr.LocalRead -> read += e.local
                is IrExpr.FieldRead -> walkExpr(e.receiver)
                is IrExpr.New -> e.args.forEach(::walkExpr)
                is IrExpr.Arith -> { walkExpr(e.l); walkExpr(e.r) }
                is IrExpr.Compare -> { walkExpr(e.l); walkExpr(e.r) }
                is IrExpr.Not -> walkExpr(e.operand)
                is IrExpr.Neg -> walkExpr(e.operand)
                is IrExpr.Convert -> walkExpr(e.expr)
                is IrExpr.IsType -> walkExpr(e.expr)
                is IrExpr.Invoke -> { walkExpr(e.receiver); e.args.forEach(::walkExpr) }
                is IrExpr.StringTemplate -> e.parts.forEach(::walkExpr)
                is IrExpr.NullGuard -> walkExpr(e.expr)
                else -> Unit
            }
        }
        fun walkStmt(s: IrStmt) {
            when (s) {
                is IrStmt.LocalAssign -> walkExpr(s.value)
                is IrStmt.Call -> { walkExpr(s.receiver); s.args.forEach(::walkExpr) }
                is IrStmt.New -> s.args.forEach(::walkExpr)
                is IrStmt.FieldAccess -> { walkExpr(s.receiver); walkExpr(s.value) }
                is IrStmt.Branch -> walkExpr(s.cond)
                is IrStmt.Return -> walkExpr(s.value)
                is IrStmt.Throw -> walkExpr(s.value)
                is IrStmt.Monitor -> walkExpr(s.expr)
                is IrStmt.Try -> {
                    s.body.forEach(::walkStmt)
                    s.catches.forEach { it.body.forEach(::walkStmt) }
                    s.finallyBody?.forEach(::walkStmt)
                }
                else -> Unit
            }
        }
        body.forEach(::walkStmt)
        return read
    }

    private fun hasSideEffect(e: IrExpr): Boolean = when (e) {
        is IrExpr.Invoke -> true
        is IrExpr.New -> true
        is IrExpr.StringTemplate -> true
        is IrExpr.NullGuard -> hasSideEffect(e.expr)
        is IrExpr.FieldRead -> e.receiver?.let { hasSideEffect(it) } ?: false
        is IrExpr.Arith -> hasSideEffect(e.l) || hasSideEffect(e.r)
        is IrExpr.Compare -> hasSideEffect(e.l) || hasSideEffect(e.r)
        is IrExpr.Not -> hasSideEffect(e.operand)
        is IrExpr.Neg -> hasSideEffect(e.operand)
        is IrExpr.Convert -> hasSideEffect(e.expr)
        is IrExpr.IsType -> hasSideEffect(e.expr)
        else -> false
    }

    // ── 冗余跳转消除 ───────────────────────────────────────────────────────────

    private fun removeRedundantJumps(body: MutableList<IrStmt>): Boolean {
        var changed = false
        val out = ArrayList<IrStmt>(body.size)
        var i = 0
        while (i < body.size) {
            val stmt = body[i]
            // Goto(L) 紧随 Label(L) → 删 Goto（落空即为目标）
            if (stmt is IrStmt.Goto && i + 1 < body.size) {
                val next = body[i + 1]
                if (next is IrStmt.Label && next.label.name == stmt.label.name) {
                    changed = true
                    i++
                    continue
                }
            }
            // Branch(c, L, L) → 删 Branch（两侧同目标）
            if (stmt is IrStmt.Branch && stmt.then.name == stmt.elseLabel.name) {
                changed = true
                i++
                continue
            }
            out += stmt
            i++
        }
        if (changed) {
            body.clear()
            body.addAll(out)
        }
        return changed
    }

    // ── 常量运算辅助 ───────────────────────────────────────────────────────────

    private fun isNumeric(v: Any?): Boolean = v is Number

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

    private fun negate(v: Any): Any = when (v) {
        is Int -> -v
        is Long -> -v
        is Float -> -v
        is Double -> -v
        is Byte -> -v
        else -> v
    }
}
