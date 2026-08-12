package yux.compiler.parser

import yux.compiler.ast.YxAccessor
import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxAnnotation
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAssign
import yux.compiler.ast.YxAsyncBlock
import yux.compiler.ast.YxAwait
import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxBreak
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxCatchClause
import yux.compiler.ast.YxCharLiteral
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxClassMember
import yux.compiler.ast.YxContinue
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxElseCondition
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxExprCondition
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.ast.YxFloatLiteral
import yux.compiler.ast.YxFor
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxFunctionType
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIf
import yux.compiler.ast.YxIfExpr
import yux.compiler.ast.YxImport
import yux.compiler.ast.YxIndexExpr
import yux.compiler.ast.YxInitBlock
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxIsCondition
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNamedType
import yux.compiler.ast.YxNode
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxPackage
import yux.compiler.ast.YxParallelBlock
import yux.compiler.ast.YxParameter
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxRange
import yux.compiler.ast.YxRawStringLiteral
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxService
import yux.compiler.ast.YxStmt
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxStringTemplate
import yux.compiler.ast.YxStringText
import yux.compiler.ast.YxSuper
import yux.compiler.ast.YxThis
import yux.compiler.ast.YxThrow
import yux.compiler.ast.YxTry
import yux.compiler.ast.YxType
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxTypeParam
import yux.compiler.ast.YxTypeReference
import yux.compiler.ast.YxUnary
import yux.compiler.ast.YxUnsafeBlock
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxVisibility
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhenBranch
import yux.compiler.ast.YxWhenExpr
import yux.compiler.ast.YxWhenExprBranch
import yux.compiler.ast.YxWhile

/**
 * AST 文本 dump（golden 快照与 `yuxc ast` 用，T-M2-10）。
 *
 * 输出稳定、逐行缩进；表达式为单行，语句/声明为树节点。
 */
object AstPrinter {
    fun dump(decls: List<YxDecl>): String =
        buildString {
            appendLine("program")
            decls.forEach { append(renderDecl(it, 1)) }
        }.trimEnd('\n')

    private fun indent(depth: Int): String = "  ".repeat(depth)

    private fun appendLine(
        sb: StringBuilder,
        depth: Int,
        line: String,
    ) {
        sb.append(indent(depth)).append(line).append('\n')
    }

    private fun renderDecl(
        decl: YxDecl,
        depth: Int,
    ): String =
        buildString {
            when (decl) {
                is YxPackage -> {
                    appendLine(this, depth, "package ${decl.name}")
                }

                is YxImport -> {
                    appendLine(
                        this,
                        depth,
                        if (decl.star) {
                            "import ${decl.qualifiedName.joinToString(
                                ".",
                            )}.*"
                        } else {
                            "import ${decl.qualifiedName.joinToString(".")}"
                        },
                    )
                }

                is YxClass -> {
                    decl.annotations.forEach { appendLine(this, depth, renderAnnotation(it)) }
                    val vis = visibilityPrefix(decl.visibility)
                    appendLine(
                        this,
                        depth,
                        "$vis${decl.name}${renderTypeParams(decl.typeParams)}${renderSuper(decl.superType, decl.interfaces)}",
                    )
                    decl.members.forEach { append(renderClassMember(it, depth + 1)) }
                }

                is YxDataClass -> {
                    decl.annotations.forEach { appendLine(this, depth, renderAnnotation(it)) }
                    val vis = visibilityPrefix(decl.visibility)
                    appendLine(
                        this,
                        depth,
                        "data $vis${decl.name}${renderTypeParams(decl.typeParams)}${renderSuper(decl.superType, decl.interfaces)}",
                    )
                    decl.members.forEach { append(renderClassMember(it, depth + 1)) }
                }

                is YxService -> {
                    decl.annotations.forEach { appendLine(this, depth, renderAnnotation(it)) }
                    appendLine(this, depth, "service ${decl.name}${renderTypeParams(decl.typeParams)}")
                    decl.members.forEach { append(renderClassMember(it, depth + 1)) }
                }

                is YxFunction -> {
                    append(renderFunction(decl, depth))
                }

                is YxProperty -> {
                    append(renderProperty(decl, depth))
                }

                is YxExtensionDecl -> {
                    appendLine(this, depth, "extension ${decl.keyword} [${decl.pluginId}]")
                }
            }
        }

    private fun renderClassMember(
        member: YxClassMember,
        depth: Int,
    ): String =
        buildString {
            when (member) {
                is YxFunction -> {
                    append(renderFunction(member, depth))
                }

                is YxProperty -> {
                    append(renderProperty(member, depth))
                }

                is YxInitBlock -> {
                    appendLine(this, depth, "init")
                    append(renderBlock(member.body, depth + 1))
                }
            }
        }

    private fun renderFunction(
        funDecl: YxFunction,
        depth: Int,
    ): String =
        buildString {
            funDecl.annotations.forEach { appendLine(this, depth, renderAnnotation(it)) }
            val prefix =
                buildString {
                    if (funDecl.isAsync) append("async ")
                    if (funDecl.isOverride) append("override ")
                    append(visibilityPrefix(funDecl.visibility))
                }
            val params = funDecl.params.joinToString(", ") { renderParameter(it) }
            val ret = funDecl.returnType?.let { ": ${renderType(it)}" } ?: ""
            appendLine(this, depth, "fun $prefix${funDecl.name}${renderTypeParams(funDecl.typeParams)}($params)$ret")
            when (val body = funDecl.body) {
                is YxFunctionBody.YxBlockBody -> append(renderBlock(body.block, depth + 1))
                is YxFunctionBody.YxExpressionBody -> appendLine(this, depth + 1, "body = ${renderExpr(body.expr)}")
            }
        }

    private fun renderProperty(
        prop: YxProperty,
        depth: Int,
    ): String =
        buildString {
            prop.annotations.forEach { appendLine(this, depth, renderAnnotation(it)) }
            val type = prop.type?.let { ": ${renderType(it)}" } ?: ""
            val init = prop.initializer?.let { " = ${renderExpr(it)}" } ?: ""
            appendLine(this, depth, "property ${prop.name}$type$init")
            prop.accessors.forEach { append(renderAccessor(it, depth + 1)) }
        }

    private fun renderAccessor(
        accessor: YxAccessor,
        depth: Int,
    ): String =
        buildString {
            appendLine(this, depth, if (accessor.kind == YxAccessorKind.GET) "accessor get" else "accessor set")
            append(renderBlock(accessor.body, depth + 1))
        }

    private fun renderParameter(param: YxParameter): String {
        val def = param.defaultValue?.let { " = ${renderExpr(it)}" } ?: ""
        return "${param.name}: ${renderType(param.type)}$def"
    }

    private fun renderAnnotation(annotation: YxAnnotation): String {
        val args =
            if (annotation.args.isNotEmpty()) {
                "(" + annotation.args.joinToString(", ") { "${it.name} = ${renderExpr(it.value)}" } + ")"
            } else {
                ""
            }
        return "@${annotation.qualifiedName.joinToString(".")}$args"
    }

    private fun renderTypeParams(params: List<YxTypeParam>): String =
        if (params.isEmpty()) "" else " " + params.joinToString(" ") { it.name }

    private fun renderSuper(
        superType: YxType?,
        interfaces: List<YxType>,
    ): String {
        val ext = superType?.let { " extends ${renderType(it)}" } ?: ""
        val impl = if (interfaces.isNotEmpty()) " implements ${interfaces.joinToString(", ") { renderType(it) }}" else ""
        return ext + impl
    }

    private fun visibilityPrefix(vis: YxVisibility): String =
        when (vis) {
            YxVisibility.PUBLIC -> ""
            YxVisibility.PRIVATE -> "private "
            YxVisibility.PROTECTED -> "protected "
        }

    // ── 语句 ──────────────────────────────────────────────────────────────────

    private fun renderStmt(
        stmt: YxStmt,
        depth: Int,
    ): String =
        buildString {
            when (stmt) {
                is YxBlock -> {
                    append(renderBlock(stmt, depth))
                }

                is YxVarDecl -> {
                    val type = stmt.type?.let { ": ${renderType(it)}" } ?: ""
                    val init = stmt.initializer?.let { " = ${renderExpr(it)}" } ?: ""
                    appendLine(this, depth, "var ${stmt.name}$type$init")
                }

                is YxAssign -> {
                    appendLine(this, depth, "assign ${renderExpr(stmt.target)} ${stmt.op} ${renderExpr(stmt.value)}")
                }

                is YxExprStmt -> {
                    appendLine(this, depth, "expr ${renderExpr(stmt.expr)}")
                }

                is YxIf -> {
                    appendLine(this, depth, "if ${renderExpr(stmt.condition)}")
                    append(renderStmt(stmt.thenBranch, depth + 1))
                    stmt.elseBranch?.let {
                        appendLine(this, depth, "else")
                        append(renderStmt(it, depth + 1))
                    }
                }

                is YxWhen -> {
                    val subj = stmt.subject?.let { " ${renderExpr(it)}" } ?: ""
                    appendLine(this, depth, "when$subj")
                    stmt.branches.forEach { append(renderWhenBranch(it, depth + 1)) }
                }

                is YxFor -> {
                    appendLine(this, depth, "for ${stmt.varName} in ${renderExpr(stmt.iterable)}")
                    append(renderStmt(stmt.body, depth + 1))
                }

                is YxWhile -> {
                    appendLine(this, depth, "while ${renderExpr(stmt.condition)}")
                    append(renderStmt(stmt.body, depth + 1))
                }

                is YxReturn -> {
                    val v = stmt.value?.let { " ${renderExpr(it)}" } ?: ""
                    appendLine(this, depth, "return$v")
                }

                is YxBreak -> {
                    appendLine(this, depth, "break")
                }

                is YxContinue -> {
                    appendLine(this, depth, "continue")
                }

                is YxTry -> {
                    appendLine(this, depth, "try")
                    append(renderStmt(stmt.body, depth + 1))
                    stmt.catches.forEach { append(renderCatch(it, depth + 1)) }
                    stmt.finallyBody?.let {
                        appendLine(this, depth, "finally")
                        append(renderStmt(it, depth + 1))
                    }
                }

                is YxAsyncBlock -> {
                    appendLine(this, depth, "async")
                    append(renderBlock(stmt.body, depth + 1))
                }

                is YxParallelBlock -> {
                    appendLine(this, depth, "parallel")
                    append(renderBlock(stmt.body, depth + 1))
                }

                is YxUnsafeBlock -> {
                    appendLine(this, depth, "unsafe")
                    append(renderBlock(stmt.body, depth + 1))
                }
            }
        }

    private fun renderWhenBranch(
        branch: YxWhenBranch,
        depth: Int,
    ): String =
        buildString {
            val cond =
                when (val c = branch.condition) {
                    is YxIsCondition -> "is ${renderType(c.type)}"
                    is YxElseCondition -> "else"
                    is YxExprCondition -> renderExpr(c.expr)
                }
            appendLine(this, depth, "branch $cond ->")
            append(renderStmt(branch.body, depth + 1))
        }

    private fun renderCatch(
        catch: YxCatchClause,
        depth: Int,
    ): String =
        buildString {
            val type = catch.type?.let { ": ${renderType(it)}" } ?: ""
            appendLine(this, depth, "catch ${catch.paramName}$type")
            append(renderStmt(catch.body, depth + 1))
        }

    private fun renderBlock(
        block: YxBlock,
        depth: Int,
    ): String =
        buildString {
            appendLine(this, depth, "block")
            block.statements.forEach { append(renderStmt(it, depth + 1)) }
        }

    // ── 类型 ──────────────────────────────────────────────────────────────────

    private fun renderType(type: YxType): String =
        when (type) {
            is YxNamedType -> {
                val args = if (type.typeArgs.isNotEmpty()) "<${type.typeArgs.joinToString(", ") { renderType(it) }}>" else ""
                "${type.segments.joinToString(".")}$args${if (type.nullable) "?" else ""}"
            }

            is YxFunctionType -> {
                "(${type.params.joinToString(", ") { renderType(it) }}) -> ${renderType(type.ret)}"
            }
        }

    // ── 表达式（单行）─────────────────────────────────────────────────────────

    private fun renderExpr(expr: YxExpr): String =
        when (expr) {
            is YxIntLiteral -> {
                "Int(${expr.text})"
            }

            is YxFloatLiteral -> {
                "Float(${expr.text})"
            }

            is YxCharLiteral -> {
                "Char(${expr.text})"
            }

            is YxBoolLiteral -> {
                if (expr.value) "Bool(true)" else "Bool(false)"
            }

            is YxNullLiteral -> {
                "Null"
            }

            is YxRawStringLiteral -> {
                "Raw(${quote(expr.text)})"
            }

            is YxStringLiteral -> {
                "Str(${quote(expr.text)})"
            }

            is YxStringText -> {
                "Text(${quote(expr.text)})"
            }

            is YxStringTemplate -> {
                "Template[${expr.parts.joinToString(", ") { renderExpr(it) }}]"
            }

            is YxIdentifier -> {
                "Id(${expr.name})"
            }

            is YxThis -> {
                "This"
            }

            is YxSuper -> {
                "Super"
            }

            is YxTypeReference -> {
                "TypeRef(${renderType(expr.type)})"
            }

            is YxMemberAccess -> {
                "Get(${renderExpr(expr.receiver)}, ${expr.name})"
            }

            is YxCall -> {
                val marker = if (expr.noParen) "CallNP" else "Call"
                "$marker(${renderExpr(expr.callee)}, [${expr.args.joinToString(", ") { renderExpr(it) }}])"
            }

            is YxTypeCall -> {
                "New(${renderType(expr.type)}, [${expr.args.joinToString(", ") { renderExpr(it) }}])"
            }

            is YxBinary -> {
                "Binary(${expr.op}, ${renderExpr(expr.left)}, ${renderExpr(expr.right)})"
            }

            is YxUnary -> {
                "Unary(${expr.op}, ${renderExpr(expr.operand)})"
            }

            is YxLambda -> {
                "Lambda([${expr.params.joinToString(", ")}], ${renderExpr(expr.body)})"
            }

            is YxBlockLambda -> {
                "BlockLambda{${inlineBlock(expr.body)}}"
            }

            is YxParen -> {
                "Paren(${renderExpr(expr.expr)})"
            }

            is YxThrow -> {
                "Throw(${renderExpr(expr.expr)})"
            }

            is YxAwait -> {
                "Await(${renderExpr(expr.operand)})"
            }

            is YxIs -> {
                "Is(${renderExpr(expr.expr)}, ${renderType(expr.type)})"
            }

            is YxAs -> {
                "As(${renderExpr(expr.expr)}, ${renderType(expr.type)})"
            }

            is YxNullable -> {
                "Nullable(${renderExpr(expr.expr)})"
            }

            is YxRange -> {
                "Range(${renderExpr(expr.from)}, ${renderExpr(expr.to)})"
            }

            is YxAssign -> {
                "Assign(${renderExpr(expr.target)}, ${expr.op}, ${renderExpr(expr.value)})"
            }

            is YxIfExpr -> {
                "IfExpr(${renderExpr(expr.condition)}, ${renderExpr(expr.thenExpr)}, ${renderExpr(expr.elseExpr)})"
            }

            is YxWhenExpr -> {
                val subj = expr.subject?.let { renderExpr(it) } ?: "-"
                "WhenExpr($subj, [${expr.branches.joinToString("; ") { renderWhenExprBranch(it) }}])"
            }

            is YxIndexExpr -> {
                "Index(${renderExpr(expr.base)}, ${renderExpr(expr.index)})"
            }
        }

    private fun renderWhenExprBranch(branch: YxWhenExprBranch): String {
        val cond =
            when (val c = branch.condition) {
                is YxIsCondition -> "is ${renderType(c.type)}"
                is YxElseCondition -> "else"
                is YxExprCondition -> renderExpr(c.expr)
            }
        return "$cond -> ${renderExpr(branch.body)}"
    }

    /** 块 Lambda 体：语句以 `; ` 连接的单行形式。 */
    private fun inlineBlock(block: YxBlock): String = block.statements.joinToString("; ") { inlineStmt(it) }

    private fun inlineStmt(stmt: YxStmt): String =
        when (stmt) {
            is YxBlock -> {
                "{ ${inlineBlock(stmt)} }"
            }

            is YxExprStmt -> {
                renderExpr(stmt.expr)
            }

            is YxVarDecl -> {
                val init = stmt.initializer?.let { " = ${renderExpr(it)}" } ?: ""
                "var ${stmt.name}$init"
            }

            is YxAssign -> {
                "${renderExpr(stmt.target)} ${stmt.op} ${renderExpr(stmt.value)}"
            }

            is YxReturn -> {
                "return${stmt.value?.let { " ${renderExpr(it)}" } ?: ""}"
            }

            is YxIf -> {
                "if ${renderExpr(stmt.condition)} ${inlineStmt(stmt.thenBranch)}" +
                    (stmt.elseBranch?.let { " else ${inlineStmt(it)}" } ?: "")
            }

            else -> {
                renderStmt(stmt, 0).trim()
            }
        }

    private fun quote(text: String): String {
        val escaped =
            text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
