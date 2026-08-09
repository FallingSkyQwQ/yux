package yux.compiler.parser

import yux.compiler.ast.SourceSpan
import yux.compiler.ast.YxAccessor
import yux.compiler.ast.YxAccessorKind
import yux.compiler.ast.YxAnnotation
import yux.compiler.ast.YxAnnotationArg
import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAsyncBlock
import yux.compiler.ast.YxAssign
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
import yux.compiler.ast.YxImport
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
import yux.compiler.ast.YxWhile

/**
 * CST → AST 规约（02-§5.2 / T-M2-9）：去除括号/换行/关键字冗余记号，
 * 产出带 [SourceSpan] 的语义节点。
 */
class CstToAst {

    /** 转换整个程序。 */
    fun convert(program: CstProgram): List<YxDecl> =
        program.decls.mapNotNull { convertTopLevel(it) }

    private fun convertTopLevel(decl: CstDecl): YxDecl? = when (decl) {
        is CstPackageDecl -> YxPackage(decl.name.joinToString(".") { it.text }, decl.span)
        is CstImportDecl -> YxImport(decl.name.map { it.text }, decl.star != null, decl.span)
        is CstClassDecl -> convertClass(decl)
        is CstDataClassDecl -> convertDataClass(decl)
        is CstServiceDecl -> convertService(decl)
        is CstFunctionDecl -> convertFunction(decl)
        is CstPropertyDecl -> convertProperty(decl, isTopLevel = true)
        is CstExtensionDecl -> YxExtensionDecl(
            keyword = decl.keyword.text,
            pluginId = decl.pluginId,
            payload = decl.payload,
            span = decl.span,
        )
    }

    private fun convertClass(decl: CstClassDecl): YxClass {
        val members = decl.body.members.mapNotNull { convertClassMember(it) }
        return YxClass(
            name = decl.name.text,
            typeParams = convertTypeParams(decl.typeParams),
            superType = decl.extendsType?.let { convertType(it) },
            interfaces = decl.implements.map { convertType(it) },
            members = members,
            visibility = convertVisibility(decl.modifier),
            annotations = decl.annotations.map { convertAnnotation(it) },
            span = decl.span,
        )
    }

    private fun convertDataClass(decl: CstDataClassDecl): YxDataClass {
        val properties = decl.body.members.map { member ->
            when (member) {
                is CstPropertyDecl -> convertProperty(member, isTopLevel = false)
                is CstFunctionDecl -> {
                    // Data classes cannot have methods; this would be caught in semantic analysis
                    // For now, we skip unsupported members but they are visible in CST
                    null
                }
                is CstInitBlock -> {
                    // Data classes cannot have init blocks; this would be caught in semantic analysis
                    null
                }
            }
        }.filterNotNull()
        return YxDataClass(
            name = decl.name.text,
            typeParams = convertTypeParams(decl.typeParams),
            superType = decl.extendsType?.let { convertType(it) },
            interfaces = decl.implements.map { convertType(it) },
            properties = properties,
            visibility = convertVisibility(decl.modifier),
            annotations = decl.annotations.map { convertAnnotation(it) },
            span = decl.span,
        )
    }

    private fun convertService(decl: CstServiceDecl): YxService {
        val members = decl.body.members.mapNotNull { convertClassMember(it) }
        return YxService(
            name = decl.name.text,
            typeParams = convertTypeParams(decl.typeParams),
            members = members,
            annotations = decl.annotations.map { convertAnnotation(it) },
            span = decl.span,
        )
    }

    private fun convertClassMember(member: CstClassMember): YxClassMember? = when (member) {
        is CstFunctionDecl -> convertFunction(member)
        is CstPropertyDecl -> convertProperty(member, isTopLevel = false)
        is CstInitBlock -> YxInitBlock(convertBlock(member.block), member.span)
    }

    private fun convertFunction(decl: CstFunctionDecl): YxFunction {
        val body: YxFunctionBody = when (val b = decl.body) {
            is CstBlockBody -> YxFunctionBody.YxBlockBody(convertBlock(b.block), b.span)
            is CstExpressionBody -> YxFunctionBody.YxExpressionBody(convertExpr(b.expr), b.span)
        }
        return YxFunction(
            name = decl.name.text,
            typeParams = convertTypeParams(decl.typeParams),
            params = decl.params.map { convertParameter(it) },
            returnType = decl.returnType?.let { convertType(it) },
            body = body,
            isAsync = decl.asyncKw != null,
            isOverride = decl.overrideKw != null,
            visibility = convertVisibility(decl.modifier),
            annotations = decl.annotations.map { convertAnnotation(it) },
            span = decl.span,
        )
    }

    private fun convertParameter(param: CstParameter): YxParameter = YxParameter(
        name = param.name.text,
        type = convertType(param.type),
        defaultValue = param.defaultValue?.let { convertExpr(it) },
        annotations = param.annotations.map { convertAnnotation(it) },
        span = param.span,
    )

    private fun convertProperty(decl: CstPropertyDecl, isTopLevel: Boolean): YxProperty = YxProperty(
        name = decl.name.text,
        type = decl.type?.let { convertType(it) },
        initializer = decl.initializer?.let { convertExpr(it) },
        accessors = decl.accessors.map { convertAccessor(it) },
        annotations = decl.annotations.map { convertAnnotation(it) },
        isTopLevel = isTopLevel,
        span = decl.span,
    )

    private fun convertAccessor(accessor: CstAccessor): YxAccessor = YxAccessor(
        kind = if (accessor.kw.text == "get") YxAccessorKind.GET else YxAccessorKind.SET,
        body = convertBlock(accessor.body),
        span = accessor.span,
    )

    private fun convertAnnotation(annotation: CstAnnotation): YxAnnotation = YxAnnotation(
        qualifiedName = annotation.name.map { it.text },
        args = annotation.args.map { arg ->
            YxAnnotationArg(arg.name.text, convertExpr(arg.value), arg.span)
        },
        span = annotation.span,
    )

    private fun convertTypeParams(tokens: List<yux.compiler.lexer.Token>): List<YxTypeParam> =
        tokens.map { YxTypeParam(it.text, SourceSpan(it.position, it.position)) }

    private fun convertVisibility(modifier: yux.compiler.lexer.Token?): YxVisibility = when (modifier?.text) {
        "private" -> YxVisibility.PRIVATE
        "protected" -> YxVisibility.PROTECTED
        else -> YxVisibility.PUBLIC
    }

    // ── 类型 ──────────────────────────────────────────────────────────────────

    private fun convertType(type: CstType): YxType = when (type) {
        is CstNamedType -> YxNamedType(
            segments = type.segments.map { it.text },
            typeArgs = type.typeArgs.map { convertType(it) },
            nullable = type.nullableKw != null,
            span = type.span,
        )
        is CstFunctionType -> YxFunctionType(
            params = type.params.map { convertType(it) },
            ret = convertType(type.ret),
            span = type.span,
        )
    }

    // ── 语句 ──────────────────────────────────────────────────────────────────

    private fun convertStmt(stmt: CstStmt): YxStmt = when (stmt) {
        is CstBlock -> convertBlock(stmt)
        is CstVarDeclStmt -> YxVarDecl(
            name = stmt.name.text,
            type = stmt.type?.let { convertType(it) },
            initializer = stmt.initializer?.let { convertExpr(it) },
            span = stmt.span,
        )
        is CstAssignStmt -> YxAssign(convertExpr(stmt.target), stmt.op.text, convertExpr(stmt.value), stmt.span)
        is CstExprStmt -> YxExprStmt(convertExpr(stmt.expr), stmt.span)
        is CstIfStmt -> YxIf(
            convertExpr(stmt.condition),
            convertStmt(stmt.thenBranch),
            stmt.elseBranch?.let { convertStmt(it) },
            stmt.span,
        )
        is CstWhenStmt -> YxWhen(
            convertExpr(stmt.subject),
            stmt.branches.map { branch ->
                YxWhenBranch(convertWhenCondition(branch.condition), convertStmt(branch.body), branch.span)
            },
            stmt.span,
        )
        is CstForStmt -> YxFor(stmt.varName.text, convertExpr(stmt.iterable), convertStmt(stmt.body), stmt.span)
        is CstWhileStmt -> YxWhile(convertExpr(stmt.condition), convertStmt(stmt.body), stmt.span)
        is CstReturnStmt -> YxReturn(stmt.value?.let { convertExpr(it) }, stmt.span)
        is CstBreakStmt -> YxBreak(stmt.span)
        is CstContinueStmt -> YxContinue(stmt.span)
        is CstTryStmt -> YxTry(
            convertStmt(stmt.body),
            stmt.catches.map { catch ->
                YxCatchClause(catch.paramName.text, catch.type?.let { convertType(it) }, convertStmt(catch.body), catch.span)
            },
            stmt.finallyBody?.let { convertStmt(it) },
            stmt.span,
        )
        is CstAsyncStmt -> YxAsyncBlock(convertBlock(stmt.block), stmt.span)
        is CstParallelStmt -> YxParallelBlock(convertBlock(stmt.block), stmt.span)
        is CstUnsafeStmt -> YxUnsafeBlock(convertBlock(stmt.block), stmt.span)
    }

    /** 公开转换入口：扩展关键字解析器下沉用（M8，把 `parseBlock()` 产出的块转为 AST）。 */
    fun convertBlock(block: CstBlock): YxBlock =
        YxBlock(block.statements.map { convertStmt(it) }, block.span)

    private fun convertWhenCondition(condition: CstWhenCondition): YxWhenConditionX = when (condition) {
        is CstIsWhenCondition -> YxIsCondition(convertType(condition.type), condition.span)
        is CstElseWhenCondition -> YxElseCondition(condition.span)
        is CstExprWhenCondition -> YxExprCondition(convertExpr(condition.expr), condition.span)
    }

    // ── 表达式 ────────────────────────────────────────────────────────────────

    private fun convertExpr(expr: CstExpr): YxExpr = when (expr) {
        is CstIntLiteral -> YxIntLiteral(expr.token.text, expr.span)
        is CstFloatLiteral -> YxFloatLiteral(expr.token.text, expr.span)
        is CstCharLiteral -> YxCharLiteral(expr.token.text, expr.span)
        is CstBoolLiteral -> YxBoolLiteral(expr.token.text == "true", expr.span)
        is CstNullLiteral -> YxNullLiteral(expr.span)
        is CstRawStringLiteral -> YxRawStringLiteral(expr.token.text, expr.span)
        is CstStringLiteral -> YxStringLiteral(expr.token.text, expr.span)
        is CstStringText -> YxStringText(expr.token.text, expr.span)
        is CstStringTemplate -> YxStringTemplate(expr.parts.map { convertExpr(it) }, expr.span)
        is CstIdentifier -> YxIdentifier(expr.token.text, expr.span)
        is CstThis -> YxThis(expr.span)
        is CstSuper -> YxSuper(expr.span)
        is CstTypeReference -> YxTypeReference(convertType(expr.type), expr.span)
        is CstMemberAccess -> YxMemberAccess(convertExpr(expr.receiver), expr.name.text, expr.span)
        is CstCall -> YxCall(
            convertExpr(expr.callee),
            expr.args.map { convertExpr(it) },
            noParen = expr.lparen == null,
            expr.span,
        )
        is CstTypeCall -> YxTypeCall(convertType(expr.type), expr.args.map { convertExpr(it) }, expr.span)
        is CstBinary -> YxBinary(expr.op.text, convertExpr(expr.left), convertExpr(expr.right), expr.span)
        is CstUnary -> YxUnary(expr.op.text, convertExpr(expr.operand), expr.span)
        is CstLambda -> YxLambda(expr.params.map { it.text }, convertExpr(expr.body), expr.span)
        is CstBlockLambda -> YxBlockLambda(convertBlock(expr.block), expr.span)
        is CstParen -> YxParen(convertExpr(expr.expr), expr.span)
        is CstThrow -> YxThrow(convertExpr(expr.expr), expr.span)
        is CstIsExpr -> YxIs(convertExpr(expr.expr), convertType(expr.type), expr.span)
        is CstAsExpr -> YxAs(convertExpr(expr.expr), convertType(expr.type), expr.span)
        is CstNullable -> YxNullable(convertExpr(expr.expr), expr.span)
        is CstRangeExpr -> YxRange(convertExpr(expr.from), convertExpr(expr.to), expr.span)
        is CstAssignmentExpr -> YxAssign(convertExpr(expr.target), expr.op.text, convertExpr(expr.value), expr.span)
    }
}

/** 便于 when 分支收窄的别名（避免长串类型）。 */
private typealias YxWhenConditionX = yux.compiler.ast.YxWhenCondition
