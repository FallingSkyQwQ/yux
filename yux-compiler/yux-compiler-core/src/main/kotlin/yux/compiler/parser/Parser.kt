package yux.compiler.parser

import yux.compiler.ast.SourceSpan
import yux.compiler.diag.DiagnosticSink
import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind
import yux.compiler.lexer.TokenKind.ARROW
import yux.compiler.lexer.TokenKind.ASSIGN
import yux.compiler.lexer.TokenKind.AT
import yux.compiler.lexer.TokenKind.COLON
import yux.compiler.lexer.TokenKind.COMMA
import yux.compiler.lexer.TokenKind.DOT
import yux.compiler.lexer.TokenKind.EOF
import yux.compiler.lexer.TokenKind.IDENTIFIER
import yux.compiler.lexer.TokenKind.INTERPOLATION_START
import yux.compiler.lexer.TokenKind.KEYWORD
import yux.compiler.lexer.TokenKind.LBRACE
import yux.compiler.lexer.TokenKind.LPAREN
import yux.compiler.lexer.TokenKind.NEWLINE
import yux.compiler.lexer.TokenKind.QUESTION
import yux.compiler.lexer.TokenKind.RANGE
import yux.compiler.lexer.TokenKind.RBRACE
import yux.compiler.lexer.TokenKind.RPAREN
import yux.compiler.lexer.TokenKind.SEMICOLON
import yux.compiler.lexer.TokenKind.SOFT_KEYWORD
import yux.compiler.lexer.TokenKind.STRING_END
import yux.compiler.lexer.TokenKind.STRING_START
import yux.compiler.lexer.TokenKind.STRING_TEXT
import yux.compiler.lexer.TokenKind.IDENTIFIER as ID
import yux.compiler.source.SourceFile

/**
 * 语法分析器（02-§5 / T-M2）：手写递归下降 + Pratt 表达式。
 *
 * 流程：词法 → 声明预收集（[DeclarationsCollector]）→ 两遍式主体解析（02-§6.2）。
 * 产出 CST，经 `CstToAst` 规约为 AST。
 */
class Parser(
    val source: SourceFile,
    val diagnostics: DiagnosticSink = DiagnosticSink(),
) {
    internal val tokens: List<Token> = yux.compiler.lexer.Lexer(source, diagnostics).tokenize()
    internal var index = 0
    internal val symbols: PreSymbolTable = DeclarationsCollector.collect(tokens)
    internal val pratt = PrattParser(this)
    internal val lambda = LambdaParser(this)

    /** 局部作用域栈（块/函数参数）。 */
    private var scope: Scope? = null

    /** 抑制 `{` 作为无括号调用实参（if/while/for 条件后的块为语句体）。 */
    internal var suppressBlockCall = false
        private set
    private var suppressBlockDepth = 0

    internal fun enterSuppressBlock() {
        suppressBlockDepth++
        suppressBlockCall = true
    }

    internal fun exitSuppressBlock() {
        suppressBlockDepth--
        if (suppressBlockDepth == 0) suppressBlockCall = false
    }

    private class Scope(val parent: Scope?) {
        val names = mutableSetOf<String>()
    }

    // ── token 游标 ────────────────────────────────────────────────────────────

    internal val current: Token get() = tokens[index.coerceAtMost(tokens.lastIndex)]

    internal fun peek(rel: Int): Token {
        val i = (index + rel).coerceIn(0, tokens.lastIndex)
        return tokens[i]
    }

    internal fun advance(): Token {
        val t = current
        if (index < tokens.lastIndex) index++
        return t
    }

    internal fun at(kind: TokenKind): Boolean = current.kind == kind

    internal fun atKeyword(word: String): Boolean = current.kind == KEYWORD && current.text == word

    internal fun atSoft(word: String): Boolean = current.kind == SOFT_KEYWORD && current.text == word

    internal fun atStatementEnd(): Boolean = when (current.kind) {
        NEWLINE, SEMICOLON, RBRACE, EOF -> true
        KEYWORD -> current.text == "else" || current.text == "catch" || current.text == "finally"
        else -> false
    }

    internal fun expect(kind: TokenKind, what: String): Token {
        if (at(kind)) return advance()
        error("expected $what, found '${current.text}'")
        return advance()
    }

    internal fun expectIdent(what: String): Token {
        if (at(IDENTIFIER)) return advance()
        error("expected $what, found '${current.text}'")
        return advance()
    }

    internal fun error(message: String, position: yux.compiler.lexer.SourcePosition = current.position) {
        diagnostics.error(message, position)
    }

    internal fun skipNewlines() {
        while (at(NEWLINE) || at(SEMICOLON)) advance()
    }

    // ── 作用域 ────────────────────────────────────────────────────────────────

    internal fun pushScope() {
        scope = Scope(scope)
    }

    internal fun popScope() {
        scope = scope?.parent
    }

    internal fun declareLocal(name: String) {
        scope?.names?.add(name)
    }

    internal fun isDeclared(name: String): Boolean {
        var s = scope
        while (s != null) {
            if (name in s.names) return true
            s = s.parent
        }
        return symbols.lookup(name) != null
    }

    // ── 入口 ──────────────────────────────────────────────────────────────────

    fun parse(): CstProgram {
        skipNewlines()
        val decls = mutableListOf<CstDecl>()
        val start = current
        while (!at(EOF)) {
            val decl = parseTopLevelDecl()
            if (decl != null) decls += decl
            skipNewlines()
        }
        val span = SourceSpan(start.position, current.position)
        return CstProgram(decls, span)
    }

    // ── 顶级声明（01-§3, §5）──────────────────────────────────────────────────

    private fun parseTopLevelDecl(): CstDecl? {
        val annotations = parseAnnotations()
        skipNewlines()
        return when {
            atKeyword("package") -> parsePackageDecl()
            atKeyword("import") -> parseImportDecl()
            atKeyword("data") -> parseDataClass(annotations, null)
            atKeyword("service") -> parseService(annotations)
            at(ID) || atSoft("private") || atSoft("protected") ||
                atKeyword("fun") || atKeyword("async") -> {
                val flags = parseDeclFlags()
                when {
                    atKeyword("fun") -> parseFunction(annotations, flags, funKw = advance())
                    atKeyword("data") -> parseDataClass(annotations, flags.modifier)
                    atKeyword("service") -> parseService(annotations)
                    at(ID) -> {
                        val nxt = peek(1)
                        if (nxt.kind == LBRACE ||
                            (nxt.kind == SOFT_KEYWORD && (nxt.text == "extends" || nxt.text == "implements"))
                        ) {
                            parseClass(annotations, flags)
                        } else {
                            parseProperty(annotations, flags.modifier)
                        }
                    }
                    else -> {
                        error("expected declaration after modifier")
                        index = Recovery.skipToLineEnd(tokens, index)
                        null
                    }
                }
            }
            at(LBRACE) -> {
                error("unexpected '{' at top level: expected declaration")
                skipBalancedBraces()
                null
            }
            else -> {
                error("unexpected token '${current.text}': expected declaration")
                index = Recovery.skipToLineEnd(tokens, index)
                null
            }
        }
    }

    private data class DeclFlags(
        val async: Token? = null,
        val override: Token? = null,
        val modifier: Token? = null,
    )

    private fun parseDeclFlags(): DeclFlags {
        var async: Token? = null
        var override: Token? = null
        var modifier: Token? = null
        var looping = true
        while (looping) {
            when {
                atKeyword("async") -> async = advance()
                atSoft("override") -> override = advance()
                atSoft("private") || atSoft("protected") -> modifier = advance()
                else -> looping = false
            }
        }
        return DeclFlags(async, override, modifier)
    }

    private fun parsePackageDecl(): CstDecl {
        val pkgKw = expect(KEYWORD, "'package'")
        val name = mutableListOf(expectIdent("package name"))
        while (at(DOT)) {
            advance()
            name += expectIdent("package name")
        }
        finishStatement()
        return CstPackageDecl(pkgKw, name, spanOf(pkgKw, name.last()))
    }

    private fun parseImportDecl(): CstDecl {
        val importKw = expect(KEYWORD, "'import'")
        val name = mutableListOf(expectIdent("import name"))
        while (at(DOT) && peek(1).kind == IDENTIFIER) {
            advance()
            name += advance()
        }
        val star = if (at(DOT) && peek(1).kind == TokenKind.STAR) {
            advance()
            advance()
        } else {
            null
        }
        finishStatement()
        return CstImportDecl(importKw, name, star, spanOf(importKw, star ?: name.last()))
    }

    private fun parseDataClass(annotations: List<CstAnnotation>, modifier: Token?): CstDecl {
        val dataKw = expect(KEYWORD, "'data'")
        val name = expectIdent("class name")
        val typeParams = parseTypeParams()
        val (extendsKw, extendsType, implementsKw, implements) = parseExtendsImplements()
        val body = parseClassBody()
        return CstDataClassDecl(
            dataKw, modifier, annotations, name, typeParams,
            extendsKw, extendsType, implementsKw, implements, body,
            spanOf(dataKw, body),
        )
    }

    private fun parseService(annotations: List<CstAnnotation>): CstDecl {
        val serviceKw = expect(KEYWORD, "'service'")
        val name = expectIdent("service name")
        val typeParams = parseTypeParams()
        val body = parseClassBody()
        return CstServiceDecl(serviceKw, annotations, name, typeParams, body, spanOf(serviceKw, body))
    }

    private fun parseClass(annotations: List<CstAnnotation>, flags: DeclFlags): CstDecl {
        val name = expectIdent("class name")
        val typeParams = parseTypeParams()
        val (extendsKw, extendsType, implementsKw, implements) = parseExtendsImplements()
        val body = parseClassBody()
        return CstClassDecl(
            flags.modifier, annotations, name, typeParams,
            extendsKw, extendsType, implementsKw, implements, body,
            spanOf(name, body),
        )
    }

    private fun parseExtendsImplements(): ExtendsImplements {
        var extendsKw: Token? = null
        var extendsType: CstType? = null
        var implementsKw: Token? = null
        val implements = mutableListOf<CstType>()
        if (atSoft("extends")) {
            extendsKw = advance()
            extendsType = parseType()
        }
        if (atSoft("implements")) {
            implementsKw = advance()
            implements += parseType()
            while (at(COMMA)) {
                advance()
                implements += parseType()
            }
        }
        return ExtendsImplements(extendsKw, extendsType, implementsKw, implements)
    }

    private data class ExtendsImplements(
        val extendsKw: Token?,
        val extendsType: CstType?,
        val implementsKw: Token?,
        val implements: List<CstType>,
    )

    private fun parseTypeParams(): List<Token> {
        val result = mutableListOf<Token>()
        while (at(ID)) result += advance()
        return result
    }

    private fun parseClassBody(): CstClassBody {
        val lbrace = expect(LBRACE, "'{'")
        pushScope()
        val members = mutableListOf<CstClassMember>()
        skipNewlines()
        while (!at(RBRACE) && !at(EOF)) {
            val member = parseClassMember()
            if (member != null) members += member
            skipNewlines()
        }
        val rbrace = if (at(RBRACE)) advance() else {
            error("expected '}' to close class body")
            current
        }
        popScope()
        return CstClassBody(lbrace, members, rbrace, SourceSpan(lbrace.position, rbrace.position))
    }

    private fun parseClassMember(): CstClassMember? {
        val annotations = parseAnnotations()
        skipNewlines()
        return when {
            at(RBRACE) || at(EOF) -> null
            atKeyword("fun") || atKeyword("async") || atSoft("override") ||
                atSoft("private") || atSoft("protected") || at(ID) || at(LBRACE) -> {
                val flags = parseDeclFlags()
                when {
                    atKeyword("fun") -> parseFunction(annotations, flags, funKw = advance())
                    at(ID) && peek(1).kind == LPAREN ->
                        parseFunction(annotations, flags, funKw = null)
                    at(ID) -> parseProperty(annotations, flags.modifier)
                    at(LBRACE) -> {
                        val b = parseBlock()
                        CstInitBlock(b, b.span)
                    }
                    else -> {
                        error("expected 'fun' or property after modifier")
                        index = Recovery.skipToLineEnd(tokens, index)
                        null
                    }
                }
            }
            else -> {
                error("unexpected token '${current.text}' in class body")
                index = Recovery.skipToLineEnd(tokens, index)
                null
            }
        }
    }

    // ── 函数 / 属性 / 参数 ────────────────────────────────────────────────────

    private fun parseFunction(
        annotations: List<CstAnnotation>,
        flags: DeclFlags,
        funKw: Token?,
    ): CstFunctionDecl {
        val name = expectIdent("function name")
        val typeParams = parseTypeParams()
        pushScope() // 参数与函数体共享作用域
        val params = parseParameters()
        val colonKw = if (at(COLON)) advance() else null
        val returnType = if (colonKw != null) parseType() else null
        val body = when {
            at(LBRACE) -> {
                val b = parseBlock()
                CstBlockBody(b, b.span)
            }
            at(ASSIGN) -> {
                val assignKw = advance()
                val expr = pratt.parseExpression()
                finishStatement()
                CstExpressionBody(assignKw, expr, spanOf(assignKw, expr))
            }
            else -> {
                error("expected function body ('{' or '= expr')")
                val empty = CstBlock(current, emptyList(), current, spanOf(current, current))
                CstBlockBody(empty, empty.span)
            }
        }
        popScope()
        return CstFunctionDecl(
            annotations, flags.async, flags.override, flags.modifier, funKw, name,
            typeParams, params, colonKw, returnType, body,
            spanOf(funKw ?: name, body),
        )
    }

    private fun parseParameters(): List<CstParameter> {
        val params = mutableListOf<CstParameter>()
        expect(LPAREN, "'('")
        skipNewlines()
        if (!at(RPAREN)) {
            while (true) {
                skipNewlines()
                val ann = parseAnnotations()
                val name = expectIdent("parameter name")
                val colonKw = expect(COLON, "':' in parameter")
                val type = parseType()
                val assignKw = if (at(ASSIGN)) advance() else null
                val defaultValue = if (assignKw != null) pratt.parseExpression() else null
                declareLocal(name.text)
                val startSpan = ann.firstOrNull()?.span ?: spanOf(name)
                val endSpan = (defaultValue ?: type)?.span ?: spanOf(name)
                params += CstParameter(
                    ann, name, colonKw, type, assignKw, defaultValue,
                    spanOf(startSpan, endSpan),
                )
                skipNewlines()
                if (at(COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }
        val rparen = expect(RPAREN, "')'")
        return params
    }

    private fun parseProperty(annotations: List<CstAnnotation>, modifier: Token?): CstPropertyDecl {
        val name = expectIdent("property name")
        val colonKw = if (at(COLON)) advance() else null
        val type = if (colonKw != null) parseType() else null
        val assignKw = if (at(ASSIGN)) advance() else null
        val initializer = if (assignKw != null) pratt.parseExpression() else null
        if (colonKw == null && assignKw == null) {
            error("expected ':' or '=' in property declaration")
        }
        val accessors = mutableListOf<CstAccessor>()
        if (at(LBRACE)) {
            val lbrace = advance()
            skipNewlines()
            while (at(ID) && (current.text == "get" || current.text == "set") && peek(1).kind == LBRACE) {
                val kw = advance()
                // S-5.2.3 访问器块内绑定：`value`=backing field；setter 内 `set`=新值
                pushScope()
                declareLocal("value")
                if (kw.text == "set") declareLocal("set")
                val block = parseBlock()
                popScope()
                accessors += CstAccessor(kw, block, spanOf(kw, block))
                skipNewlines()
            }
            if (!at(RBRACE)) {
                error("expected '}' to close accessor block")
                index = Recovery.skipToLineEnd(tokens, index)
            } else {
                advance()
            }
            if (accessors.isEmpty()) {
                error("expected 'get' or 'set' accessor in '{ ... }'", lbrace.position)
            }
        }
        val startSpan = annotations.firstOrNull()?.span ?: spanOf(name)
        val endNode: CstNode? = accessors.lastOrNull() ?: initializer ?: type
        val endSpan = endNode?.span ?: spanOf(name)
        return CstPropertyDecl(
            annotations, modifier, name, colonKw, type, assignKw, initializer, accessors,
            spanOf(startSpan, endSpan),
        )
    }

    // ── 注解（01-§8.2）────────────────────────────────────────────────────────

    private fun parseAnnotations(): List<CstAnnotation> {
        val result = mutableListOf<CstAnnotation>()
        while (at(AT)) {
            val atKw = advance()
            val name = mutableListOf(expectIdent("annotation name"))
            while (at(DOT)) {
                advance()
                name += expectIdent("annotation name")
            }
            var lparen: Token? = null
            val args = mutableListOf<CstAnnotationArg>()
            var rparen: Token? = null
            if (at(LPAREN)) {
                lparen = advance()
                skipNewlines()
                if (!at(RPAREN)) {
                    while (true) {
                        val argName = expectIdent("annotation argument name")
                        val assignKw = expect(ASSIGN, "'='")
                        val value = pratt.parseExpression()
                        args += CstAnnotationArg(argName, assignKw, value, spanOf(argName, value))
                        skipNewlines()
                        if (at(COMMA)) {
                            advance()
                            continue
                        }
                        break
                    }
                }
                rparen = expect(RPAREN, "')'")
            }
            result += CstAnnotation(atKw, name, lparen, args, rparen, spanOf(atKw, rparen ?: name.last()))
        }
        return result
    }

    // ── 类型（01-§4）──────────────────────────────────────────────────────────

    /**
     * 解析类型（01-§4.1）。命名类型的类型实参按「已知泛型元数」消费（S-4.3.2），
     * 未知类型视为非泛型（M3 以 ClassPathSymbolProvider 补全）。
     *
     * [allowDotted]：是否允许限定名分段。表达式位置的类型引用不允许
     * （`System.currentTimeMillis` 是静态成员访问，01-§7.7 规则 3），
     * 类型位置允许（`java.util.List`）。
     */
    internal fun parseType(allowDotted: Boolean = true): CstType {
        if (at(LPAREN)) {
            val lparen = advance()
            val params = mutableListOf<CstType>()
            skipNewlines()
            if (!at(RPAREN)) {
                while (true) {
                    params += parseType()
                    skipNewlines()
                    if (at(COMMA)) {
                        advance()
                        skipNewlines()
                        continue
                    }
                    break
                }
            }
            val rparen = expect(RPAREN, "')'")
            val arrow = expect(ARROW, "'->'")
            val ret = parseType()
            return CstFunctionType(lparen, params, rparen, arrow, ret, spanOf(lparen, ret))
        }
        val segments = mutableListOf<Token>()
        segments += expectIdent("type name")
        if (allowDotted) {
            while (at(DOT) && peek(1).kind == IDENTIFIER) {
                advance()
                segments += advance()
            }
        }
        val typeArgs = mutableListOf<CstType>()
        val arity = symbols.typeParamCount(segments.first().text)
        for (i in 0 until arity) {
            if (at(ID)) typeArgs += parseType() else break
        }
        val nullable = if (at(QUESTION)) advance() else null
        return CstNamedType(segments, typeArgs, nullable, spanOf(segments.first(), nullable ?: segments.last()))
    }

    // ── 语句（01-§6）──────────────────────────────────────────────────────────

    private fun parseStatement(): CstStmt {
        return when {
            at(LBRACE) -> parseBlock()
            atKeyword("if") -> parseIf()
            atKeyword("when") -> parseWhen()
            atKeyword("for") -> parseFor()
            atKeyword("while") -> parseWhile()
            atKeyword("return") -> parseReturn()
            atKeyword("break") -> {
                val kw = advance()
                finishStatement()
                CstBreakStmt(kw, spanOf(kw, kw))
            }
            atKeyword("continue") -> {
                val kw = advance()
                finishStatement()
                CstContinueStmt(kw, spanOf(kw, kw))
            }
            atKeyword("try") -> parseTry()
            atKeyword("async") -> {
                val kw = advance()
                val block = parseBlock()
                CstAsyncStmt(kw, block, spanOf(kw, block))
            }
            atKeyword("parallel") -> {
                val kw = advance()
                val block = parseBlock()
                CstParallelStmt(kw, block, spanOf(kw, block))
            }
            atKeyword("unsafe") -> {
                val kw = advance()
                val block = parseBlock()
                CstUnsafeStmt(kw, block, spanOf(kw, block))
            }
            at(ID) && peek(1).kind == COLON -> parseVarDeclWithType()
            at(ID) && peek(1).kind == ASSIGN && !isDeclared(current.text) -> parseVarDeclInferred()
            else -> parseExpressionStatement()
        }
    }

    private fun parseVarDeclWithType(): CstStmt {
        val name = advance()
        val colonKw = expect(COLON, "':'")
        val type = parseType()
        val assignKw = if (at(ASSIGN)) advance() else null
        val initializer = if (assignKw != null) pratt.parseExpression() else null
        finishStatement()
        declareLocal(name.text)
        return CstVarDeclStmt(name, colonKw, type, assignKw, initializer, spanOf(name, initializer ?: type))
    }

    private fun parseVarDeclInferred(): CstStmt {
        val name = advance()
        val assignKw = expect(ASSIGN, "'='")
        val initializer = pratt.parseExpression()
        finishStatement()
        declareLocal(name.text)
        return CstVarDeclStmt(name, null, null, assignKw, initializer, spanOf(name, initializer))
    }

    private fun parseExpressionStatement(): CstStmt {
        val expr = pratt.parseExpression()
        finishStatement()
        return if (expr is CstAssignmentExpr) {
            CstAssignStmt(expr.target, expr.op, expr.value, expr.span)
        } else {
            CstExprStmt(expr, expr.span)
        }
    }

    private fun parseIf(): CstStmt {
        val ifKw = expect(KEYWORD, "'if'")
        val condition = parseCondition()
        val thenBranch = parseStatementBody()
        skipNewlines()
        var elseKw: Token? = null
        var elseBranch: CstStmt? = null
        if (atKeyword("else")) {
            elseKw = advance()
            skipNewlines()
            elseBranch = if (atKeyword("if")) parseIf() else parseStatementBody()
        }
        return CstIfStmt(ifKw, condition, thenBranch, elseKw, elseBranch, spanOf(ifKw, elseBranch ?: thenBranch))
    }

    private fun parseWhen(): CstStmt {
        val whenKw = expect(KEYWORD, "'when'")
        val subject = parseCondition()
        val lbrace = expect(LBRACE, "'{'")
        val branches = mutableListOf<CstWhenBranch>()
        skipNewlines()
        while (!at(RBRACE) && !at(EOF)) {
            skipNewlines()
            if (at(RBRACE)) break
            val condition: CstWhenCondition
            if (atKeyword("else")) {
                val kw = advance()
                condition = CstElseWhenCondition(kw, spanOf(kw, kw))
            } else if (atKeyword("is")) {
                val kw = advance()
                val type = parseType()
                condition = CstIsWhenCondition(kw, type, spanOf(kw, type))
            } else {
                val expr = parseCondition()
                condition = CstExprWhenCondition(expr, expr.span)
            }
            val arrow = expect(ARROW, "'->'")
            val body = parseStatementBody()
            branches += CstWhenBranch(condition, arrow, body, spanOf(condition, body))
            skipNewlines()
        }
        val rbrace = expect(RBRACE, "'}'")
        return CstWhenStmt(whenKw, subject, lbrace, branches, rbrace, spanOf(whenKw, rbrace))
    }

    private fun parseFor(): CstStmt {
        val forKw = expect(KEYWORD, "'for'")
        val varName = expectIdent("loop variable")
        val inKw = expect(KEYWORD, "'in'")
        val iterable = parseCondition()
        val body = parseStatementBody()
        return CstForStmt(forKw, varName, inKw, iterable, body, spanOf(forKw, body))
    }

    private fun parseWhile(): CstStmt {
        val whileKw = expect(KEYWORD, "'while'")
        val condition = parseCondition()
        val body = parseStatementBody()
        return CstWhileStmt(whileKw, condition, body, spanOf(whileKw, body))
    }

    private fun parseReturn(): CstStmt {
        val returnKw = expect(KEYWORD, "'return'")
        val value = if (atStatementEnd()) null else pratt.parseExpression()
        finishStatement()
        val endSpan = value?.span ?: spanOf(returnKw)
        return CstReturnStmt(returnKw, value, spanOf(spanOf(returnKw), endSpan))
    }

    private fun parseTry(): CstStmt {
        val tryKw = expect(KEYWORD, "'try'")
        val body: CstStmt = if (at(LBRACE)) {
            parseBlock()
        } else {
            val expr = pratt.parseExpression(suppressBlock = true)
            finishStatement()
            CstExprStmt(expr, expr.span)
        }
        val catches = mutableListOf<CstCatchClause>()
        var finallyKw: Token? = null
        var finallyBody: CstStmt? = null
        skipNewlines()
        while (atKeyword("catch")) {
            val catchKw = advance()
            val param = expectIdent("catch parameter")
            val colonKw = if (at(COLON)) advance() else null
            val type = if (colonKw != null) parseType() else null
            val cbody = parseStatementBody()
            catches += CstCatchClause(catchKw, param, colonKw, type, cbody, spanOf(catchKw, cbody))
            skipNewlines()
        }
        if (atKeyword("finally")) {
            finallyKw = advance()
            finallyBody = parseStatementBody()
        }
        return CstTryStmt(tryKw, body, catches, finallyKw, finallyBody, spanOf(tryKw, finallyBody ?: body))
    }

    /** 条件表达式：`{` 不作为无括号调用实参（其后为语句体）。 */
    private fun parseCondition(): CstExpr = pratt.parseExpression(suppressBlock = true)

    /** 语句体：块或单行语句（if/when/for/while 的 then 分支）。 */
    private fun parseStatementBody(): CstStmt = if (at(LBRACE)) parseBlock() else parseStatement()

    internal fun parseBlock(): CstBlock {
        val lbrace = expect(LBRACE, "'{'")
        pushScope()
        val statements = mutableListOf<CstStmt>()
        skipNewlines()
        while (!at(RBRACE) && !at(EOF)) {
            statements += parseStatement()
            skipNewlines()
        }
        val rbrace = if (at(RBRACE)) advance() else {
            error("expected '}' to close block")
            current
        }
        popScope()
        return CstBlock(lbrace, statements, rbrace, SourceSpan(lbrace.position, rbrace.position))
    }

    internal fun finishStatement() {
        if (at(NEWLINE) || at(SEMICOLON)) {
            advance()
            skipNewlines()
        } else if (atStatementEnd()) {
            // 块内最后语句 / else / catch 前，允许省略终止符
        } else {
            error("expected newline or ';'")
        }
    }

    /** 跳过平衡花括号块（错误恢复用）。 */
    private fun skipBalancedBraces() {
        if (!at(LBRACE)) return
        var depth = 0
        while (!at(EOF)) {
            when {
                at(LBRACE) -> {
                    depth++
                    advance()
                }
                at(RBRACE) -> {
                    depth--
                    advance()
                    if (depth == 0) return
                }
                else -> advance()
            }
        }
    }

    // ── 主表达式（01-§7，Pratt 前缀）──────────────────────────────────────────

    /** 解析一个 Primary 表达式（含构造/类型引用消歧，01-§7.7）。 */
    internal fun parsePrimaryExpr(): CstExpr {
        val t = current
        return when {
            t.kind == TokenKind.INT_LITERAL -> CstIntLiteral(advance(), spanOf(t))
            t.kind == TokenKind.FLOAT_LITERAL -> CstFloatLiteral(advance(), spanOf(t))
            t.kind == TokenKind.CHAR_LITERAL -> CstCharLiteral(advance(), spanOf(t))
            t.kind == TokenKind.STRING_LITERAL -> CstStringLiteral(advance(), spanOf(t))
            t.kind == TokenKind.RAW_STRING_LITERAL -> CstRawStringLiteral(advance(), spanOf(t))
            t.kind == STRING_START -> parseStringTemplate()
            t.kind == KEYWORD && t.text == "true" -> CstBoolLiteral(advance(), spanOf(t))
            t.kind == KEYWORD && t.text == "false" -> CstBoolLiteral(advance(), spanOf(t))
            t.kind == KEYWORD && t.text == "null" -> CstNullLiteral(advance(), spanOf(t))
            t.kind == KEYWORD && t.text == "this" -> CstThis(advance(), spanOf(t))
            t.kind == KEYWORD && t.text == "super" -> CstSuper(advance(), spanOf(t))
            t.kind == KEYWORD && t.text == "throw" -> {
                advance()
                val expr = pratt.parseExpression()
                CstThrow(t, expr, spanOf(t, expr))
            }
            t.kind == IDENTIFIER -> {
                if (peek(1).kind == ARROW) {
                    lambda.parseArrowLambda()
                } else if (symbols.isType(t.text) || looksLikeTypeName(t.text)) {
                    parseTypeCallOrRef()
                } else {
                    CstIdentifier(advance(), spanOf(t))
                }
            }
            t.kind == LPAREN -> {
                if (lambda.looksLikeParenLambda()) {
                    lambda.parseParenLambda()
                } else {
                    val lparen = advance()
                    val expr = pratt.parseExpression()
                    val rparen = expect(RPAREN, "')'")
                    CstParen(lparen, expr, rparen, spanOf(lparen, rparen))
                }
            }
            t.kind == LBRACE -> lambda.parseBlockLambda()
            else -> {
                error("unexpected token '${t.text}' in expression")
                advance()
                CstIdentifier(current, spanOf(current))
            }
        }
    }

    /** 命名约定回退（01-§7.7 规则 4）：首字母大写视为类型。 */
    private fun looksLikeTypeName(name: String): Boolean =
        name.firstOrNull()?.isUpperCase() == true

    private fun parseTypeCallOrRef(): CstExpr {
        // 表达式位置的类型引用不允许限定名分段（`System.currentTimeMillis` 为静态访问）
        val type = parseType(allowDotted = false)
        if (at(LPAREN)) {
            val lparen = advance()
            val callArgs = parseCallArguments()
            return CstTypeCall(type, lparen, callArgs.args, callArgs.rparen, spanOf(type, callArgs.rparen))
        }
        return CstTypeReference(type, type.span)
    }

    /** `(` 之后的实参列表 + 收尾 `)`。 */
    internal class CallArgs(val args: List<CstExpr>, val rparen: Token)

    internal fun parseCallArguments(): CallArgs {        val args = mutableListOf<CstExpr>()
        skipNewlines()
        if (!at(RPAREN)) {
            while (true) {
                skipNewlines()
                args += parseArgument()
                skipNewlines()
                if (at(COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }
        val rparen = expect(RPAREN, "')'")
        return CallArgs(args, rparen)
    }

    private fun parseArgument(): CstExpr {
        if (at(LBRACE)) return lambda.parseBlockLambda()
        // `(a, b -> body)` 括号 Lambda 作实参（01-§7.4 示例）
        if (at(LPAREN) && lambda.looksLikeParenLambda()) return lambda.parseParenLambda()
        return pratt.parseExpression()
    }

    /** 插值串（02-§4.3 / S-7.6.1）。 */
    private fun parseStringTemplate(): CstExpr {
        val start = advance() // STRING_START
        val parts = mutableListOf<CstExpr>()
        while (!at(STRING_END) && !at(EOF)) {
            when {
                at(STRING_TEXT) -> {
                    val tok = advance()
                    parts += CstStringText(tok, spanOf(tok))
                }
                at(INTERPOLATION_START) -> {
                    val dollar = advance()
                    when {
                        at(LBRACE) -> {
                            advance()
                            val expr = pratt.parseExpression()
                            expect(RBRACE, "'}'")
                            parts += expr
                        }
                        at(ID) -> parts += CstIdentifier(advance(), spanOf(current))
                        else -> error("expected identifier or '{' after '\$'", dollar.position)
                    }
                }
                else -> {
                    error("unexpected token '${current.text}' in string template")
                    advance()
                }
            }
        }
        val end = expect(STRING_END, "'\"'")
        return CstStringTemplate(start, parts, end, spanOf(start, end))
    }
}
