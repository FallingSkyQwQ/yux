package yux.compiler.ir

/**
 * IR 表达式单行渲染（T-M4-6，[IrPrinter] 的表达式部分）。
 *
 * 输出稳定、确定性：常量按字面量规则渲染、字段读取按「this/静态/复合」三种接收者
 * 约定渲染、调用以 displayName + 接收者优先的实参列表渲染。类型一律用 [IrType.render]。
 */
internal object IrExprRenderer {

    fun render(expr: IrExpr): String = when (expr) {
        is IrExpr.Const -> "Const(${renderLiteral(expr.value)})"
        is IrExpr.This -> "This"
        is IrExpr.LocalRead -> "LocalRead(${localIndex(expr.local)})"
        is IrExpr.FieldRead -> "FieldRead(${receiverPrefix(expr.receiver)}${expr.field.name})"
        is IrExpr.New -> "New ${expr.type.render()} [${exprList(expr.args)}]"
        is IrExpr.Arith -> "Arith(${expr.op.name}, ${render(expr.l)}, ${render(expr.r)})"
        is IrExpr.Compare -> "Compare(${expr.op.name}, ${render(expr.l)}, ${render(expr.r)})"
        is IrExpr.Not -> "Not(${render(expr.operand)})"
        is IrExpr.Neg -> "Neg(${render(expr.operand)})"
        is IrExpr.Convert -> "Convert(${render(expr.expr)}, ${expr.to.render()})"
        is IrExpr.IsType -> "IsType(${render(expr.expr)}, ${expr.type.render()})"
        is IrExpr.Invoke -> renderInvoke(expr)
        is IrExpr.FnInvoke -> "FnInvoke ${render(expr.fn)} [${exprList(expr.args)}]"
        is IrExpr.StringTemplate -> "StringTemplate [${exprList(expr.parts)}]"
        is IrExpr.NullGuard -> "NullGuard(${render(expr.expr)})"
        is IrExpr.Lambda -> {
            val suffix = if (expr.captures.isEmpty()) "" else " [${exprList(expr.captures)}]"
            "Lambda ${expr.target.method.name}$suffix"
        }
    }

    private fun renderInvoke(expr: IrExpr.Invoke): String {
        val entries = buildList {
            expr.receiver?.let { add(render(it)) }
            addAll(expr.args.map { render(it) })
        }
        return "Invoke ${expr.target.displayName} [${entries.joinToString(", ")}]"
    }

    private fun renderLiteral(value: Any?): String = when (value) {
        is Int -> value.toString()
        is Long -> "${value}L"
        is Float -> "${value}F"
        is Double -> value.toString()
        is Boolean -> value.toString()
        is Char -> "'${escapeChar(value)}'"
        is String -> "\"${escapeString(value)}\""
        null -> "null"
        is Unit -> "Unit"
        else -> value.toString()
    }

    private fun escapeChar(c: Char): String = when (c) {
        '\n' -> "\\n"
        '\t' -> "\\t"
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '"' -> "\\\""
        else -> c.toString()
    }

    private fun escapeString(s: String): String = buildString {
        s.forEach { c ->
            append(when (c) {
                '\n' -> "\\n"
                '\t' -> "\\t"
                '\\' -> "\\\\"
                '"' -> "\\\""
                '$' -> "\\$"
                else -> c.toString()
            })
        }
    }
}

// ── 共享渲染辅助（[IrPrinter] 与表达式渲染共用）───────────────────────────────

/** 缩进行：每级 1 空格。 */
internal fun line(depth: Int, text: String): String = " ".repeat(depth) + text + "\n"

/** 接收者渲染：null→空串（静态）、[IrExpr.This]→`this`、其余→括号包裹。 */
internal fun receiverText(receiver: IrExpr?): String = when (receiver) {
    null -> ""
    is IrExpr.This -> "this"
    else -> "(${IrExprRenderer.render(receiver)})"
}

/** 字段访问的接收者段：非静态接收者后补 `.` 分隔符。 */
internal fun receiverPrefix(receiver: IrExpr?): String {
    val text = receiverText(receiver)
    return if (text.isEmpty()) "" else "$text."
}

/** 局部槽位 `#<index>`；未知（负）索引回退 `#?`。 */
internal fun localIndex(local: IrLocal): String = if (local.index >= 0) "#${local.index}" else "#?"

/** 表达式列表的元素串（`[a, b]` 括号内的部分）。 */
internal fun exprList(exprs: List<IrExpr>): String =
    exprs.joinToString(", ") { IrExprRenderer.render(it) }
