package yux.compiler.ir

/**
 * IR 文本 dump（T-M4-6 / 02-§8）：供 `yuxc ir` 子命令与 `.ir` golden 快照使用。
 *
 * 输出契约（golden 快照逐字节锁定）：
 * - 逐行缩进：类 1 空格、成员 2 空格、语句 3 空格、Try 嵌套体 4 空格；
 * - 严格按插入顺序输出（模块类/字段/属性/方法/语句均不排序），保证确定性；
 * - 表达式由 [IrExprRenderer] 单行内联；类型统一用 [IrType.render]；
 * - dump 以单个换行符结尾。
 */
object IrPrinter {

    /** 整个模块的可读文本 dump。 */
    fun dump(module: IrModule): String = buildString {
        appendLine("module {")
        module.classes.forEach { append(renderClass(it, 1)) }
        appendLine("}")
    }

    private fun renderClass(cls: IrClass, depth: Int): String = buildString {
        val kind = when {
            cls.isData -> " (data)"
            cls.isService -> " (service)"
            cls.isFileClass -> " (file)"
            else -> ""
        }
        val typeParams = if (cls.typeParams.isEmpty()) "" else "<${cls.typeParams.joinToString(", ")}>"
        append(line(depth, "class ${cls.name}$typeParams$kind {"))
        cls.fields.forEach { append(line(depth + 1, "field ${it.name}: ${it.type.render()}")) }
        cls.properties.forEach { append(renderProperty(it, depth + 1)) }
        cls.methods.forEach { append(renderMethod(it, depth + 1)) }
        append(line(depth, "}"))
    }

    private fun renderProperty(prop: IrProperty, depth: Int): String {
        val getter = prop.getter?.let { "get: ${it.name}()" }
        val setter = prop.setter?.let { setter ->
            val type = setter.params.firstOrNull()?.type ?: prop.type
            "set: ${setter.name}(${type.render()})"
        }
        val accessors = listOfNotNull(getter, setter).joinToString(", ")
        val suffix = if (accessors.isEmpty()) "" else " [$accessors]"
        return line(depth, "property ${prop.name}: ${prop.type.render()}$suffix")
    }

    private fun renderMethod(method: IrMethod, depth: Int): String = buildString {
        val prefix = buildString {
            if (method.isStatic) append("static ")
            if (method.isOverride) append("override ")
            if (method.isAsync) append("async ")
            if (method.isSynthetic) append("synthetic ")
            append("method ")
        }
        val params = method.params.joinToString(", ") { "${it.name}: ${it.type.render()}" }
        append(line(depth, "$prefix${method.name}($params): ${method.returnType.render()} {"))
        method.body.forEach { append(renderStmt(it, depth + 1)) }
        append(line(depth, "}"))
    }

    // ── 语句 ──────────────────────────────────────────────────────────────────

    private fun renderStmt(stmt: IrStmt, depth: Int): String = when (stmt) {
        is IrStmt.Label -> line(depth, "Label ${stmt.label.name}")
        is IrStmt.LocalAssign -> {
            val local = "LocalAssign ${localIndex(stmt.local)}: ${stmt.local.type.render()}"
            line(depth, "$local = ${IrExprRenderer.render(stmt.value)}")
        }
        is IrStmt.Call -> {
            val entries = buildList {
                stmt.receiver?.let { add(IrExprRenderer.render(it)) }
                addAll(stmt.args.map { IrExprRenderer.render(it) })
            }
            line(depth, "Call ${stmt.callee.displayName} [${
                entries.joinToString(", ")
            }] : ${stmt.ret.render()}")
        }
        is IrStmt.New -> line(depth, "New ${stmt.type.render()} [${exprList(stmt.args)}]")
        is IrStmt.Eval -> line(depth, "Eval ${IrExprRenderer.render(stmt.expr)}")
        is IrStmt.FieldAccess -> renderFieldAccess(stmt, depth)
        is IrStmt.Branch ->
            line(depth, "Branch ${IrExprRenderer.render(stmt.cond)} ${stmt.then.name} ${stmt.elseLabel.name}")
        is IrStmt.Goto -> line(depth, "Goto ${stmt.label.name}")
        is IrStmt.Return -> {
            val value = stmt.value?.let { " ${IrExprRenderer.render(it)}" } ?: ""
            line(depth, "Return$value")
        }
        is IrStmt.Throw -> line(depth, "Throw ${IrExprRenderer.render(stmt.value)}")
        is IrStmt.Monitor -> line(
            depth,
            if (stmt.enter) {
                "MonitorEnter ${IrExprRenderer.render(stmt.expr)}"
            } else {
                "MonitorExit ${IrExprRenderer.render(stmt.expr)}"
            },
        )
        is IrStmt.Try -> renderTry(stmt, depth)
        is IrStmt.Nop -> line(depth, "Nop")
    }

    private fun renderFieldAccess(stmt: IrStmt.FieldAccess, depth: Int): String {
        val receiver = receiverPrefix(stmt.receiver)
        val value = stmt.value?.let { IrExprRenderer.render(it) } ?: "null"
        return if (stmt.write) {
            line(depth, "FieldWrite $receiver${stmt.field.name} = $value")
        } else {
            line(depth, "FieldRead $receiver${stmt.field.name}")
        }
    }

    private fun renderTry(stmt: IrStmt.Try, depth: Int): String = buildString {
        append(line(depth, "Try"))
        stmt.body.forEach { append(renderStmt(it, depth + 1)) }
        stmt.catches.forEach { c ->
            val type = c.type?.let { ": ${it.render()}" } ?: ""
            append(line(depth, "Catch ${c.paramName}$type"))
            c.body.forEach { append(renderStmt(it, depth + 1)) }
        }
        stmt.finallyBody?.let { fin ->
            append(line(depth, "Finally"))
            fin.forEach { append(renderStmt(it, depth + 1)) }
        }
        append(line(depth, "EndTry"))
    }
}
