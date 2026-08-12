package yux.compiler.fmt

import yux.compiler.diag.DiagnosticSink
import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind
import yux.compiler.lexer.TokenKind.AT
import yux.compiler.lexer.TokenKind.COLON
import yux.compiler.lexer.TokenKind.COMMA
import yux.compiler.lexer.TokenKind.DOT
import yux.compiler.lexer.TokenKind.EOF
import yux.compiler.lexer.TokenKind.INTERPOLATION_START
import yux.compiler.lexer.TokenKind.LBRACE
import yux.compiler.lexer.TokenKind.LBRACKET
import yux.compiler.lexer.TokenKind.LPAREN
import yux.compiler.lexer.TokenKind.NEWLINE
import yux.compiler.lexer.TokenKind.QUESTION
import yux.compiler.lexer.TokenKind.RANGE
import yux.compiler.lexer.TokenKind.RBRACKET
import yux.compiler.lexer.TokenKind.RPAREN
import yux.compiler.lexer.TokenKind.SEMICOLON
import yux.compiler.lexer.TokenKind.STRING_END
import yux.compiler.lexer.TokenKind.STRING_START
import yux.compiler.lexer.TokenKind.STRING_TEXT
import yux.compiler.lexer.Trivia
import yux.compiler.parser.CstAccessor
import yux.compiler.parser.CstAnnotation
import yux.compiler.parser.CstAnnotationArg
import yux.compiler.parser.CstAsExpr
import yux.compiler.parser.CstAssignStmt
import yux.compiler.parser.CstAssignmentExpr
import yux.compiler.parser.CstAsyncStmt
import yux.compiler.parser.CstAwait
import yux.compiler.parser.CstBinary
import yux.compiler.parser.CstBlock
import yux.compiler.parser.CstBlockBody
import yux.compiler.parser.CstBlockLambda
import yux.compiler.parser.CstBoolLiteral
import yux.compiler.parser.CstBreakStmt
import yux.compiler.parser.CstCall
import yux.compiler.parser.CstCatchClause
import yux.compiler.parser.CstCharLiteral
import yux.compiler.parser.CstClassBody
import yux.compiler.parser.CstClassDecl
import yux.compiler.parser.CstContinueStmt
import yux.compiler.parser.CstDataClassDecl
import yux.compiler.parser.CstElseWhenCondition
import yux.compiler.parser.CstExpr
import yux.compiler.parser.CstExprStmt
import yux.compiler.parser.CstExprWhenCondition
import yux.compiler.parser.CstExpressionBody
import yux.compiler.parser.CstExtensionDecl
import yux.compiler.parser.CstFloatLiteral
import yux.compiler.parser.CstForStmt
import yux.compiler.parser.CstFunctionDecl
import yux.compiler.parser.CstFunctionType
import yux.compiler.parser.CstIdentifier
import yux.compiler.parser.CstIfExpr
import yux.compiler.parser.CstIfStmt
import yux.compiler.parser.CstImportDecl
import yux.compiler.parser.CstIndexExpr
import yux.compiler.parser.CstInitBlock
import yux.compiler.parser.CstIntLiteral
import yux.compiler.parser.CstIsExpr
import yux.compiler.parser.CstIsWhenCondition
import yux.compiler.parser.CstLambda
import yux.compiler.parser.CstMemberAccess
import yux.compiler.parser.CstNamedType
import yux.compiler.parser.CstNode
import yux.compiler.parser.CstNullLiteral
import yux.compiler.parser.CstNullable
import yux.compiler.parser.CstPackageDecl
import yux.compiler.parser.CstParallelStmt
import yux.compiler.parser.CstParameter
import yux.compiler.parser.CstParen
import yux.compiler.parser.CstProgram
import yux.compiler.parser.CstPropertyDecl
import yux.compiler.parser.CstRangeExpr
import yux.compiler.parser.CstRawStringLiteral
import yux.compiler.parser.CstReturnStmt
import yux.compiler.parser.CstServiceDecl
import yux.compiler.parser.CstStmt
import yux.compiler.parser.CstStringLiteral
import yux.compiler.parser.CstStringTemplate
import yux.compiler.parser.CstStringText
import yux.compiler.parser.CstSuper
import yux.compiler.parser.CstThis
import yux.compiler.parser.CstThrow
import yux.compiler.parser.CstTryStmt
import yux.compiler.parser.CstType
import yux.compiler.parser.CstTypeCall
import yux.compiler.parser.CstTypeReference
import yux.compiler.parser.CstUnary
import yux.compiler.parser.CstUnsafeStmt
import yux.compiler.parser.CstVarDeclStmt
import yux.compiler.parser.CstWhenBranch
import yux.compiler.parser.CstWhenCondition
import yux.compiler.parser.CstWhenExpr
import yux.compiler.parser.CstWhenExprBranch
import yux.compiler.parser.CstWhenStmt
import yux.compiler.parser.CstWhileStmt
import yux.compiler.parser.Parser
import yux.compiler.plugin.PluginManager
import yux.compiler.source.SourceFile

/**
 * Yux 源码格式化器（M16，T-M16-3）：基于 CST 的规范排版（gofmt 式）。
 *
 * 设计要点：
 * - 解析失败返回 null（并填充 [diagnostics]）；格式化只接受语法合法输入。
 * - 布局由 CST 驱动（换行/缩进/块结构），行内 token 间距由分隔符表决定，
 *   访问器按上下文覆盖（调用 `f(`、一元 `-x`、空/单语句块 Lambda、字符串插值等）。
 * - 解析器"消费但未存储"的 token（插值 `${`/`}`、扩展函数 `.`、参数括号、逗号、
 *   插件 payload）由游标间隙机制按源码顺序自动补发；插件扩展块整体原样拷贝。
 * - 注释一律保留：与上一 token 同源行则同行输出，否则独占一行。
 * - 幂等：`format(format(x)) == format(x)`（测试锁定）。
 *
 * 规则快览：
 * - 缩进 4 空格；块 `{` 同行开头、`}` 独占一行；空块为 `{ }`。
 * - 顶层声明/函数成员之间空一行；块内保留用户空行（无注释间隔时）。
 * - `f(x)`、`a.b`、`a[0]`、`a..b`、`T?`、`${x}`、`"`text`"` 无空格；
 *   `a = b`、`a + b`、`x: T`、`fun f() {` 有空格。
 */
class YuxFormatter(
    private val indentWidth: Int = 4,
    private val pluginManager: PluginManager = PluginManager(),
) {
    /** 格式化源码；解析失败返回 null 并填充 [diagnostics]。 */
    fun format(
        source: String,
        diagnostics: DiagnosticSink = DiagnosticSink(),
    ): String? {
        val sourceFile = SourceFile("<fmt>", source)
        val parser = Parser(sourceFile, diagnostics, pluginManager)
        val program = parser.parse()
        if (diagnostics.hasErrors) return null
        return Emitter(source, indentWidth, parser.tokens).emit(program)
    }

    /** 行内分隔。 */
    private enum class Sep {
        SPACE,
        NONE,
    }

    /** 记号流 → 规范文本的发射器。 */
    private inner class Emitter(
        private val src: String,
        private val indentWidth: Int,
        tokens: List<Token>,
    ) {
        private val out = StringBuilder()
        private var indent = 0
        private var pendingNewlines = 0
        private var lastEmittedEnd = 0
        private var atLineStart = true
        private var prevKind: TokenKind? = null

        /** 下一 token 的分隔覆盖：null=表默认，SPACE/NONE=强制。 */
        private var pendingSep: Sep? = null
        private var skipNextTrivia = false

        /** 未发射的 token 游标（含 NEWLINE/SEMICOLON/EOF 与"被消费未存储"的标点）。 */
        private val gaps: ArrayDeque<Token> = ArrayDeque(tokens)

        fun emit(program: CstProgram): String {
            program.decls.forEachIndexed { i, decl ->
                if (i > 0) {
                    val prev = program.decls[i - 1]
                    // import 组内不空行；package 后、import 组后、其余声明间空一行
                    val importGroup = decl is CstImportDecl && prev is CstImportDecl
                    if (importGroup) newline() else blankLine()
                }
                emitNode(decl)
                newline()
            }
            drainTail()
            return out.toString().trimEnd() + "\n"
        }

        // ── 布局原语 ─────────────────────────────────────────────────────────

        fun newline() {
            pendingNewlines = maxOf(pendingNewlines, 1)
        }

        fun blankLine() {
            pendingNewlines = 2
        }

        fun indentInc() {
            indent++
        }

        fun indentDec() {
            indent--
        }

        /** 下一 token 前强制无空格。 */
        fun noSpace() {
            pendingSep = Sep.NONE
        }

        /** 下一 token 前强制一个空格。 */
        fun space() {
            pendingSep = Sep.SPACE
        }

        /** 发射单个记号（含前导注释；explicit 覆盖分隔符表默认）。 */
        fun token(
            t: Token,
            explicit: Sep? = null,
        ) {
            if (t.kind == NEWLINE || t.kind == SEMICOLON || t.kind == EOF) return
            processGapsUntil(t)
            // 消费目标 token 本身（防止再次作为间隙补发）
            if (gaps.isNotEmpty() && gaps.first() === t) gaps.removeFirst()
            emitTrivia(t)
            flushLine()
            applySep(explicit, t)
            out.append(t.text)
            lastEmittedEnd = t.position.offset + t.text.length
        }

        /** 显式发射下一个间隙 token（用于"被消费未存储"的结构括号，如属性 accessors 的外层 `{}`）。
         * 仅当下一真实 token 种类匹配时发射；返回是否发射。
         */
        fun gap(
            kind: TokenKind,
            explicit: Sep? = null,
        ): Boolean {
            while (gaps.isNotEmpty() && gaps.first().kind in SKIP_KINDS) {
                emitTrivia(gaps.removeFirst())
            }
            val t = gaps.firstOrNull() ?: return false
            if (t.kind != kind) return false
            gaps.removeFirst()
            emitTrivia(t)
            flushLine()
            applySep(explicit, t)
            out.append(t.text)
            return true
        }

        /** 原样拷贝源码区间（插件扩展块 payload，含内部注释/空白/换行）。
         * 去掉拷贝区尾部空白，避免与声明间 blankLine() 叠加导致空行逐次增长（幂等性）。
         */
        fun rawCopy(
            startOffset: Int,
            endOffset: Int,
        ) {
            var end = endOffset
            while (end > startOffset && src[end - 1].isWhitespace()) {
                end--
            }
            if (end <= startOffset) return
            flushLine()
            out.append(src, startOffset, end)
            skipNextTrivia = true
            pendingSep = null
            prevKind = null
            atLineStart = false
            while (gaps.isNotEmpty() && gaps.first().position.offset < end) {
                gaps.removeFirst()
            }
        }

        // ── 间隙与注释 ───────────────────────────────────────────────────────

        /** 按源码顺序补发未存储的间隙 token；NEWLINE/SEMICOLON 跳过（只取其注释）。 */
        private fun processGapsUntil(target: Token) {
            while (gaps.isNotEmpty() && gaps.first() !== target) {
                val t = gaps.removeFirst()
                if (t.kind == NEWLINE || t.kind == SEMICOLON || t.kind == EOF) {
                    emitTrivia(t)
                } else {
                    emitTrivia(t)
                    flushLine()
                    applySep(null, t)
                    out.append(t.text)
                }
            }
        }

        /** 发射 token 前导注释。行内判定（统一规则）：
         *  - 注释与前 token 同源行（无换行间隔）→ 行内跟随；
         *  - 注释后 token 同源行（无换行间隔）→ 行首注释、后随代码同行（`/* c */ z`）；
         *  - 否则独占一行。
         */
        private fun emitTrivia(t: Token) {
            if (skipNextTrivia) {
                skipNextTrivia = false
                return
            }
            // 注意：NEWLINE/STRING_END 等 token 的 position 记录在文本之后（lexer 先消费再取位置）
            val textStart =
                when (t.kind) {
                    NEWLINE, STRING_END -> t.position.offset - t.text.length
                    else -> t.position.offset
                }
            var triviaStart = textStart - t.leadingTrivia.sumOf { it.text.length }
            for (trivia in t.leadingTrivia) {
                if (trivia is Trivia.Whitespace) {
                    triviaStart += trivia.text.length
                    continue
                }
                val commentStart = triviaStart
                val commentEnd = triviaStart + trivia.text.length
                triviaStart = commentEnd
                val beforeNL =
                    lastEmittedEnd == 0 ||
                        src.substring(lastEmittedEnd, commentStart).contains('\n')
                // 换行检测终点用 position.offset：NEWLINE token 的 position 在其文本（换行）之后
                val afterNL = src.substring(commentEnd, t.position.offset).contains('\n')
                when {
                    !beforeNL -> {
                        // 行内跟随前 token（`x = 1 // c`）：撤下待换行，恢复布局换行
                        val saved = pendingNewlines
                        pendingNewlines = 0
                        flushLine()
                        out.append(' ')
                        out.append(trivia.text)
                        atLineStart = false
                        pendingNewlines = if (afterNL) maxOf(saved, 1) else saved
                    }

                    afterNL -> {
                        // 独占一行
                        flushLine()
                        out.append(trivia.text)
                        atLineStart = false
                        pendingNewlines = 1
                    }

                    else -> {
                        // 行首注释、后随代码同行（`/* c */ z`）
                        flushLine()
                        out.append(trivia.text)
                        atLineStart = false
                        pendingNewlines = 0
                    }
                }
            }
        }

        /** 文件尾：补发剩余 NEWLINE/EOF token 的注释。 */
        private fun drainTail() {
            while (gaps.isNotEmpty()) {
                val t = gaps.removeFirst()
                if (t.kind != NEWLINE && t.kind != EOF) break
                emitTrivia(t)
            }
        }

        private fun flushLine() {
            if (pendingNewlines > 0) {
                repeat(pendingNewlines) { out.append('\n') }
                out.append(indentStr())
                pendingNewlines = 0
                pendingSep = null
                atLineStart = true
            }
        }

        private fun indentStr(): String = " ".repeat(indent * indentWidth)

        /** 决定并应用 token 前的分隔：explicit > 强制覆盖 > 分隔符表；行首恒无空格。 */
        private fun applySep(
            explicit: Sep?,
            t: Token,
        ) {
            val sep =
                if (atLineStart) {
                    Sep.NONE
                } else {
                    explicit ?: pendingSep ?: tableSep(prevKind, t.kind)
                }
            if (sep == Sep.SPACE) out.append(' ')
            pendingSep = null
            atLineStart = false
            prevKind = t.kind
        }

        // ── 节点访问器 ───────────────────────────────────────────────────────

        private fun emitNode(node: CstNode?) {
            when (node) {
                null -> {
                    Unit
                }

                is CstPackageDecl -> {
                    token(node.pkgKw)
                    node.name.forEach { token(it) }
                }

                is CstImportDecl -> {
                    token(node.importKw)
                    node.name.forEach { token(it) }
                    node.star?.let { token(it) }
                }

                is CstClassDecl -> {
                    emitClassLike(
                        annotations = node.annotations,
                        modifiers = listOfNotNull(node.modifier, node.sealedKw),
                        head = { token(node.name) },
                        typeParams = node.typeParams,
                        extendsKw = node.extendsKw,
                        extendsType = node.extendsType,
                        implementsKw = node.implementsKw,
                        implements = node.implements,
                        body = node.body,
                    )
                }

                is CstDataClassDecl -> {
                    emitClassLike(
                        annotations = node.annotations,
                        modifiers = listOfNotNull(node.dataKw, node.modifier, node.sealedKw),
                        head = { token(node.name) },
                        typeParams = node.typeParams,
                        extendsKw = node.extendsKw,
                        extendsType = node.extendsType,
                        implementsKw = node.implementsKw,
                        implements = node.implements,
                        body = node.body,
                    )
                }

                is CstServiceDecl -> {
                    emitClassLike(
                        annotations = node.annotations,
                        modifiers = listOf(node.serviceKw),
                        head = { token(node.name) },
                        typeParams = node.typeParams,
                        extendsKw = null,
                        extendsType = null,
                        implementsKw = null,
                        implements = emptyList(),
                        body = node.body,
                    )
                }

                is CstFunctionDecl -> {
                    emitFunction(node)
                }

                is CstPropertyDecl -> {
                    emitProperty(node)
                }

                is CstInitBlock -> {
                    emitBlock(node.block)
                }

                is CstBlockBody -> {
                    emitBlock(node.block)
                }

                is CstExpressionBody -> {
                    token(node.assignKw)
                    emitExpr(node.expr)
                }

                is CstExtensionDecl -> {
                    emitExtension(node)
                }

                is CstBlock -> {
                    emitBlock(node)
                }

                is CstIfStmt -> {
                    emitIf(node)
                }

                is CstWhenStmt -> {
                    emitWhenStmt(node)
                }

                is CstWhenExpr -> {
                    emitWhenExpr(node)
                }

                is CstForStmt -> {
                    token(node.forKw)
                    token(node.varName)
                    token(node.inKw)
                    emitExpr(node.iterable)
                    emitBody(node.body)
                }

                is CstWhileStmt -> {
                    token(node.whileKw)
                    emitExpr(node.condition)
                    emitBody(node.body)
                }

                is CstTryStmt -> {
                    emitTry(node)
                }

                is CstAsyncStmt -> {
                    token(node.kw)
                    emitBlock(node.block)
                }

                is CstParallelStmt -> {
                    token(node.kw)
                    emitBlock(node.block)
                }

                is CstUnsafeStmt -> {
                    token(node.kw)
                    emitBlock(node.block)
                }

                is CstVarDeclStmt -> {
                    token(node.name)
                    node.colonKw?.let { token(it) }
                    node.type?.let { emitType(it) }
                    node.assignKw?.let { token(it) }
                    node.initializer?.let { emitExpr(it) }
                }

                is CstAssignStmt -> {
                    emitExpr(node.target)
                    token(node.op)
                    emitExpr(node.value)
                }

                is CstExprStmt -> {
                    emitExpr(node.expr)
                }

                is CstReturnStmt -> {
                    token(node.returnKw)
                    node.value?.let { emitExpr(it) }
                }

                is CstBreakStmt -> {
                    token(node.kw)
                }

                is CstContinueStmt -> {
                    token(node.kw)
                }

                is CstExpr -> {
                    emitExpr(node)
                }

                is CstType -> {
                    emitType(node)
                }

                is CstParameter -> {
                    emitParameter(node)
                }

                is CstAnnotation -> {
                    emitAnnotation(node)
                }

                is CstAnnotationArg -> {
                    emitAnnotationArg(node)
                }

                is CstAccessor -> {
                    emitAccessor(node)
                }

                is CstClassBody -> {
                    emitClassBody(node)
                }

                is CstWhenBranch -> {
                    emitWhenBranch(node)
                }

                is CstWhenExprBranch -> {
                    emitWhenExprBranch(node)
                }

                is CstWhenCondition -> {
                    emitWhenCondition(node)
                }

                is CstCatchClause -> {
                    emitCatchClause(node)
                }

                is CstStringText -> {
                    token(node.token)
                }

                else -> {
                    Unit
                }
            }
        }

        // ── 声明 ─────────────────────────────────────────────────────────────

        private fun emitClassLike(
            annotations: List<CstAnnotation>,
            modifiers: List<Token>,
            head: () -> Unit,
            typeParams: List<Token>,
            extendsKw: Token?,
            extendsType: CstType?,
            implementsKw: Token?,
            implements: List<CstType>,
            body: CstClassBody,
        ) {
            annotations.forEach {
                emitAnnotation(it)
                newline()
            }
            modifiers.forEach { token(it) }
            head()
            emitTypeParams(typeParams)
            if (extendsKw != null) {
                token(extendsKw)
                emitType(extendsType)
            }
            if (implementsKw != null) {
                token(implementsKw)
                implements.forEach { emitType(it) }
            }
            emitClassBody(body)
        }

        private fun emitFunction(node: CstFunctionDecl) {
            node.annotations.forEach {
                emitAnnotation(it)
                newline()
            }
            node.asyncKw?.let { token(it) }
            node.overrideKw?.let { token(it) }
            node.modifier?.let { token(it) }
            node.funKw?.let { token(it) }
            node.receiverType?.let { emitType(it) } // 扩展函数：间隙 `.` 自动补发
            token(node.name)
            emitTypeParams(node.typeParams)
            noSpace() // 参数括号 `(`（间隙 token）无空格
            node.params.forEach { emitParameter(it) }
            node.colonKw?.let { token(it) }
            node.returnType?.let { emitType(it) }
            emitNode(node.body)
        }

        private fun emitProperty(node: CstPropertyDecl) {
            node.annotations.forEach {
                emitAnnotation(it)
                newline()
            }
            node.modifier?.let { token(it) }
            token(node.name)
            node.colonKw?.let { token(it) }
            node.type?.let { emitType(it) }
            node.assignKw?.let { token(it) }
            node.initializer?.let { emitExpr(it) }
            if (node.accessors.isNotEmpty()) {
                // 外层 `{` `}` 为间隙 token：显式发射并控制换行/缩进
                gap(TokenKind.LBRACE, Sep.SPACE)
                newline()
                indentInc()
                node.accessors.forEach {
                    emitAccessor(it)
                    newline()
                }
                indentDec()
                gap(TokenKind.RBRACE, Sep.NONE)
            }
        }

        private fun emitAccessor(node: CstAccessor) {
            token(node.kw)
            // `set(v)`：kw 后紧跟 `(` 时无空格（括号为间隙 token）
            val kwEnd = node.kw.position.offset + node.kw.text.length
            if (src.getOrNull(kwEnd) == '(') noSpace()
            emitBlock(node.body)
        }

        private fun emitClassBody(node: CstClassBody) {
            token(node.lbrace)
            if (node.members.isEmpty()) {
                space()
                token(node.rbrace)
                return
            }
            newline()
            indentInc()
            var prevIsFunction = false
            node.members.forEach { member ->
                if (prevIsFunction) blankLine() else newline()
                emitNode(member)
                prevIsFunction = member is CstFunctionDecl || member is CstInitBlock
            }
            newline()
            indentDec()
            token(node.rbrace)
        }

        private fun emitBlock(block: CstBlock) {
            token(block.lbrace)
            if (block.statements.isEmpty()) {
                space()
                token(block.rbrace)
                return
            }
            newline()
            indentInc()
            var prevEnd = block.lbrace.position.offset + 1
            block.statements.forEach { stmt ->
                val gap = src.substring(prevEnd, stmt.span.start.offset)
                val blank =
                    gap.count { it == '\n' } >= 2 &&
                        !gap.contains("//") && !gap.contains("/*")
                if (blank) blankLine() else newline()
                emitNode(stmt)
                prevEnd = stmt.span.end.offset
            }
            newline()
            indentDec()
            token(block.rbrace)
        }

        private fun emitBody(body: CstStmt) {
            if (body is CstBlock) {
                emitBlock(body)
            } else {
                newline()
                indentInc()
                emitNode(body)
                newline()
                indentDec()
            }
        }

        private fun emitIf(node: CstIfStmt) {
            token(node.ifKw)
            emitExpr(node.condition)
            emitBody(node.thenBranch)
            if (node.elseKw != null) {
                token(node.elseKw)
                if (node.elseBranch is CstIfStmt) {
                    emitIf(node.elseBranch)
                } else {
                    emitBody(node.elseBranch!!)
                }
            }
        }

        private fun emitWhenStmt(node: CstWhenStmt) {
            token(node.whenKw)
            node.subject?.let { emitExpr(it) }
            token(node.lbrace)
            newline()
            indentInc()
            node.branches.forEach {
                emitWhenBranch(it)
                newline()
            }
            indentDec()
            token(node.rbrace)
        }

        private fun emitWhenExpr(node: CstWhenExpr) {
            token(node.whenKw)
            node.subject?.let { emitExpr(it) }
            token(node.lbrace)
            newline()
            indentInc()
            node.branches.forEach {
                emitWhenExprBranch(it)
                newline()
            }
            indentDec()
            token(node.rbrace)
        }

        private fun emitWhenBranch(node: CstWhenBranch) {
            emitWhenCondition(node.condition)
            token(node.arrow)
            if (node.body is CstBlock) emitBlock(node.body) else emitNode(node.body)
        }

        private fun emitWhenExprBranch(node: CstWhenExprBranch) {
            emitWhenCondition(node.condition)
            token(node.arrow)
            emitExpr(node.body)
        }

        private fun emitWhenCondition(node: CstWhenCondition) {
            when (node) {
                is CstIsWhenCondition -> {
                    token(node.isKw)
                    emitType(node.type)
                }

                is CstElseWhenCondition -> {
                    token(node.elseKw)
                }

                is CstExprWhenCondition -> {
                    emitExpr(node.expr)
                }
            }
        }

        private fun emitTry(node: CstTryStmt) {
            token(node.tryKw)
            emitBody(node.body)
            node.catches.forEach { emitCatchClause(it) }
            if (node.finallyKw != null) {
                token(node.finallyKw)
                emitBody(node.finallyBody!!)
            }
        }

        private fun emitCatchClause(node: CstCatchClause) {
            token(node.catchKw)
            token(node.paramName)
            node.colonKw?.let { token(it) }
            node.type?.let { emitType(it) }
            emitBody(node.body)
        }

        private fun emitExtension(node: CstExtensionDecl) {
            token(node.keyword)
            val kwEnd = node.keyword.position.offset + node.keyword.text.length
            rawCopy(kwEnd, node.span.end.offset)
        }

        // ── 参数 / 注解 ───────────────────────────────────────────────────────

        private fun emitParameter(node: CstParameter) {
            node.annotations.forEach { emitAnnotation(it) }
            token(node.name)
            token(node.colonKw)
            emitType(node.type)
            node.assignKw?.let { token(it) }
            node.defaultValue?.let { emitExpr(it) }
        }

        private fun emitAnnotation(node: CstAnnotation) {
            token(node.atKw)
            node.name.forEach { token(it) }
            node.lparen?.let { token(it, Sep.NONE) }
            node.args.forEach { emitAnnotationArg(it) }
            node.rparen?.let { token(it) }
        }

        private fun emitAnnotationArg(node: CstAnnotationArg) {
            token(node.name)
            token(node.assignKw)
            emitExpr(node.value)
        }

        private fun emitTypeParams(typeParams: List<Token>) {
            typeParams.forEach { token(it) }
        }

        // ── 类型 ─────────────────────────────────────────────────────────────

        private fun emitType(type: CstType?) {
            when (type) {
                is CstNamedType -> {
                    type.segments.forEach { token(it) } // 限定名 `.` 间隙自动补发
                    type.typeArgs.forEach { emitType(it) } // `Map String Int` 空格分隔
                    type.nullableKw?.let { token(it) } // `T?` 前无空格
                }

                is CstFunctionType -> {
                    token(type.lparen, Sep.SPACE) // `: (Int) -> B`
                    type.params.forEach { emitType(it) }
                    token(type.rparen)
                    token(type.arrow)
                    emitType(type.ret)
                }

                null -> {
                    Unit
                }
            }
        }

        // ── 表达式 ───────────────────────────────────────────────────────────

        private fun emitExpr(expr: CstExpr) {
            when (expr) {
                is CstIntLiteral -> {
                    token(expr.token)
                }

                is CstFloatLiteral -> {
                    token(expr.token)
                }

                is CstCharLiteral -> {
                    token(expr.token)
                }

                is CstBoolLiteral -> {
                    token(expr.token)
                }

                is CstNullLiteral -> {
                    token(expr.token)
                }

                is CstRawStringLiteral -> {
                    token(expr.token)
                }

                is CstStringLiteral -> {
                    token(expr.token)
                }

                is CstIdentifier -> {
                    token(expr.token)
                }

                is CstThis -> {
                    token(expr.token)
                }

                is CstSuper -> {
                    token(expr.token)
                }

                is CstStringText -> {
                    token(expr.token)
                }

                is CstStringTemplate -> {
                    emitStringTemplate(expr)
                }

                is CstTypeReference -> {
                    emitType(expr.type)
                }

                is CstMemberAccess -> {
                    emitExpr(expr.receiver)
                    token(expr.dot)
                    token(expr.name)
                }

                is CstCall -> {
                    emitCall(expr)
                }

                is CstTypeCall -> {
                    emitType(expr.type)
                    token(expr.lparen!!, Sep.NONE)
                    expr.args.forEach { emitExpr(it) }
                    token(expr.rparen)
                }

                is CstBinary -> {
                    emitExpr(expr.left)
                    token(expr.op)
                    emitExpr(expr.right)
                }

                is CstWhenExpr -> {
                    emitWhenExpr(expr)
                }

                is CstIfExpr -> {
                    token(expr.ifKw)
                    emitExpr(expr.condition)
                    token(expr.thenKw)
                    emitExpr(expr.thenExpr)
                    expr.elseKw?.let { token(it) }
                    expr.elseExpr?.let { emitExpr(it) }
                }

                is CstIndexExpr -> {
                    emitExpr(expr.base)
                    token(expr.lbracket, Sep.NONE)
                    emitExpr(expr.index)
                    token(expr.rbracket)
                }

                is CstUnary -> {
                    token(expr.op)
                    noSpace()
                    emitExpr(expr.operand)
                }

                is CstAwait -> {
                    token(expr.awaitKw)
                    emitExpr(expr.operand)
                }

                is CstLambda -> {
                    emitLambda(expr)
                }

                is CstBlockLambda -> {
                    emitBlockLambda(expr)
                }

                is CstParen -> {
                    token(expr.lparen)
                    emitExpr(expr.expr)
                    token(expr.rparen)
                }

                is CstThrow -> {
                    token(expr.throwKw)
                    emitExpr(expr.expr)
                }

                is CstIsExpr -> {
                    emitExpr(expr.expr)
                    token(expr.isKw)
                    emitType(expr.type)
                }

                is CstAsExpr -> {
                    emitExpr(expr.expr)
                    token(expr.asKw)
                    emitType(expr.type)
                }

                is CstNullable -> {
                    emitExpr(expr.expr)
                    token(expr.questionKw)
                }

                is CstRangeExpr -> {
                    emitExpr(expr.from)
                    token(expr.op)
                    emitExpr(expr.to)
                }

                is CstAssignmentExpr -> {
                    emitExpr(expr.target)
                    token(expr.op)
                    emitExpr(expr.value)
                }

                else -> {
                    Unit
                }
            }
        }

        private fun emitCall(call: CstCall) {
            emitExpr(call.callee)
            if (call.lparen != null) {
                token(call.lparen!!, Sep.NONE)
                call.args.forEach { emitExpr(it) }
                token(call.rparen!!)
            } else {
                // 无括号单参调用：`print "Hello"`
                call.args.forEach {
                    space()
                    emitExpr(it)
                }
            }
        }

        private fun emitLambda(node: CstLambda) {
            node.lparen?.let { token(it) } // 括号 Lambda：前有空格与否由表决定
            node.params.forEach { token(it) }
            token(node.arrow)
            emitExpr(node.body)
        }

        private fun emitBlockLambda(node: CstBlockLambda) {
            val block = node.block
            token(block.lbrace)
            when {
                block.statements.isEmpty() -> {
                    space()
                    token(block.rbrace)
                }

                block.statements.size == 1 -> {
                    space()
                    emitNode(block.statements.first())
                    space()
                    token(block.rbrace)
                }

                else -> {
                    newline()
                    indentInc()
                    block.statements.forEach {
                        emitNode(it)
                        newline()
                    }
                    indentDec()
                    token(block.rbrace)
                }
            }
        }

        private fun emitStringTemplate(node: CstStringTemplate) {
            token(node.startKw)
            node.parts.forEach { part ->
                if (part is CstStringText) {
                    token(part.token, Sep.NONE)
                } else {
                    // 插值表达式：间隙 `${`/`$` 自动补发；表达式后紧跟 `}` 无空格
                    emitExpr(part)
                    noSpace()
                }
            }
            noSpace()
            token(node.endKw)
        }
    }

    companion object {
        /** 布局记号：发射时跳过（只取其注释）。 */
        private val SKIP_KINDS = setOf(NEWLINE, SEMICOLON, EOF)

        /** token 种类 → 其后不可空格（`f(`、`a[0]`、`a.b`、`@A`、`"`、`${`、`{`）。 */
        private val NO_SPACE_AFTER =
            setOf(
                LPAREN,
                LBRACKET,
                LBRACE,
                DOT,
                AT,
                INTERPOLATION_START,
                STRING_START,
                STRING_TEXT,
            )

        /** token 种类 → 其前不可空格（`)`、`]`、`,`、`:`、`.`、`?`、`"`）。 */
        private val NO_SPACE_BEFORE =
            setOf(
                RPAREN,
                RBRACKET,
                COMMA,
                COLON,
                DOT,
                QUESTION,
                STRING_END,
            )

        private fun tableSep(
            prev: TokenKind?,
            next: TokenKind,
        ): Sep {
            if (prev == null) return Sep.NONE
            if (prev in NO_SPACE_AFTER) return Sep.NONE
            if (next in NO_SPACE_BEFORE) return Sep.NONE
            if (prev == RANGE || next == RANGE) return Sep.NONE
            return Sep.SPACE
        }
    }
}
