package yux.compiler.parser

import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind
import yux.compiler.lexer.TokenKind.AND
import yux.compiler.lexer.TokenKind.ARROW
import yux.compiler.lexer.TokenKind.ASSIGN
import yux.compiler.lexer.TokenKind.CHAR_LITERAL
import yux.compiler.lexer.TokenKind.COMMA
import yux.compiler.lexer.TokenKind.DOT
import yux.compiler.lexer.TokenKind.EOF
import yux.compiler.lexer.TokenKind.EQ
import yux.compiler.lexer.TokenKind.FLOAT_LITERAL
import yux.compiler.lexer.TokenKind.GE
import yux.compiler.lexer.TokenKind.GT
import yux.compiler.lexer.TokenKind.IDENTIFIER
import yux.compiler.lexer.TokenKind.INT_LITERAL
import yux.compiler.lexer.TokenKind.KEYWORD
import yux.compiler.lexer.TokenKind.LBRACE
import yux.compiler.lexer.TokenKind.LBRACKET
import yux.compiler.lexer.TokenKind.LE
import yux.compiler.lexer.TokenKind.LPAREN
import yux.compiler.lexer.TokenKind.LT
import yux.compiler.lexer.TokenKind.MINUS
import yux.compiler.lexer.TokenKind.MINUS_ASSIGN
import yux.compiler.lexer.TokenKind.NEQ
import yux.compiler.lexer.TokenKind.NOT
import yux.compiler.lexer.TokenKind.OR
import yux.compiler.lexer.TokenKind.PERCENT
import yux.compiler.lexer.TokenKind.PERCENT_ASSIGN
import yux.compiler.lexer.TokenKind.PLUS
import yux.compiler.lexer.TokenKind.PLUS_ASSIGN
import yux.compiler.lexer.TokenKind.QUESTION
import yux.compiler.lexer.TokenKind.RANGE
import yux.compiler.lexer.TokenKind.RAW_STRING_LITERAL
import yux.compiler.lexer.TokenKind.RBRACE
import yux.compiler.lexer.TokenKind.RBRACKET
import yux.compiler.lexer.TokenKind.RPAREN
import yux.compiler.lexer.TokenKind.SEMICOLON
import yux.compiler.lexer.TokenKind.SLASH
import yux.compiler.lexer.TokenKind.SLASH_ASSIGN
import yux.compiler.lexer.TokenKind.STAR
import yux.compiler.lexer.TokenKind.STAR_ASSIGN
import yux.compiler.lexer.TokenKind.STRING_LITERAL
import yux.compiler.lexer.TokenKind.STRING_START
import yux.compiler.lexer.TokenKind.NEWLINE

/**
 * Pratt 表达式解析（02-§5.1 / T-M2-2、T-M2-7）。
 *
 * 优先级表（01-§7.1，自低而高）：
 *   1  赋值（右结合）     `= += -= *= /= %=`
 *   2  区间                `..`
 *   3  `||`
 *   4  `&&`
 *   5  `== !=`
 *   6  `< > <= >=` 与 `is`/`as`（右操作数为 Type）
 *   7  `+ -`
 *   8  `* / %`
 *   9  一元                `! -`
 *   10 后缀                `.prop` / `(args)` / 无括号单参 / `?`
 *
 * 无括号单参调用（S-7.2.2）：实参为紧跟的单个 Primary（含其后缀链），
 * 绑定优先级高于二元运算符——`f a + b` == `f(a) + b`。
 */
internal class PrattParser(private val p: Parser) {

    /** 二元中缀优先级；返回 null 表示非中缀。第二项为是否右结合。 */
    private fun infixInfo(tok: Token): Pair<Int, Boolean>? = when (tok.kind) {
        ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN -> 1 to true
        RANGE -> 2 to false
        OR -> 3 to false
        AND -> 4 to false
        EQ, NEQ -> 5 to false
        LT, GT, LE, GE -> 6 to false
        PLUS, MINUS -> 7 to false
        STAR, SLASH, PERCENT -> 8 to false
        else -> null
    }

    /** `is`/`as`（TypeTest，01-§7.1）。 */
    private fun isTypeTest(tok: Token): Boolean =
        tok.kind == KEYWORD && (tok.text == "is" || tok.text == "as")

    fun parseExpression(minBp: Int = 0, suppressBlock: Boolean = false): CstExpr {
        if (suppressBlock) p.enterSuppressBlock()
        val result = parseExpressionInner(minBp)
        if (suppressBlock) p.exitSuppressBlock()
        return result
    }

    private fun parseExpressionInner(minBp: Int): CstExpr {
        var left = parsePrefix()
        left = parsePostfixChain(left)
        while (true) {
            val t = p.current
            // `..=` 复合赋值（AssignOp 之一，01-§6.1）
            if (t.kind == RANGE && p.peek(1).kind == ASSIGN && 1 >= minBp) {
                val range = p.advance()
                val assign = p.advance()
                val right = parseExpression(1)
                return CstAssignmentExpr(left, Token(range.position, ASSIGN, "..="), right, spanOf(left, right))
            }
            if (isTypeTest(t)) {
                if (6 < minBp) break
                p.advance()
                val type = p.parseType()
                left = if (t.text == "is") {
                    CstIsExpr(left, t, type, spanOf(left, type))
                } else {
                    CstAsExpr(left, t, type, spanOf(left, type))
                }
                continue
            }
            val info = infixInfo(t) ?: break
            val bp = info.first
            val rightAssoc = info.second
            if (bp < minBp) break
            p.advance()
            val right = parseExpression(if (rightAssoc) bp else bp + 1)
            left = when {
                isAssignOp(t.kind) -> CstAssignmentExpr(left, t, right, spanOf(left, right))
                t.kind == RANGE -> CstRangeExpr(left, t, right, spanOf(left, right))
                else -> CstBinary(left, t, right, spanOf(left, right))
            }
        }
        return left
    }

    private fun isAssignOp(kind: TokenKind): Boolean = when (kind) {
        ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN -> true
        else -> false
    }

    /** 前缀：一元 `!`/`-` 或 Primary。后缀链在表达式层应用。 */
    private fun parsePrefix(): CstExpr {
        val t = p.current
        if (t.kind == NOT || t.kind == MINUS) {
            p.advance()
            val operand = parsePrefix()
            val operandPostfix = parsePostfixChain(operand)
            return CstUnary(t, operandPostfix, spanOf(t, operandPostfix))
        }
        return p.parsePrimaryExpr()
    }

    /** 后缀链：`.prop` / `(args)` / 无括号单参 / `?`（01-§7.1 Postfix）。 */
    private fun parsePostfixChain(expr: CstExpr): CstExpr {
        var result = expr
        while (true) {
            val t = p.current
            when {
                t.kind == DOT -> {
                    p.advance()
                    val name = p.expectIdent("member name")
                    result = CstMemberAccess(result, t, name, spanOf(result, name))
                }
                t.kind == LPAREN -> {
                    if (p.lambda.looksLikeParenLambda()) {
                        // `callee (a, b -> body)`：括号 Lambda 作无括号单参（01-§7.4）
                        val arg = p.lambda.parseParenLambda()
                        result = CstCall(result, null, listOf(arg), null, spanOf(result, arg))
                    } else {
                        val lparen = p.advance()
                        val callArgs = p.parseCallArguments()
                        result = CstCall(result, lparen, callArgs.args, callArgs.rparen, spanOf(result, callArgs.rparen))
                    }
                }
                t.kind == QUESTION -> {
                    p.advance()
                    result = CstNullable(result, t, spanOf(result, t))
                }
                t.kind == LBRACKET -> {
                    val lbracket = p.advance()
                    val index = parseExpression()
                    val rbracket = p.expect(RBRACKET, "']'")
                    result = CstIndexExpr(result, lbracket, index, rbracket, spanOf(result, rbracket))
                }
                isNoParenArgStart(t) -> {
                    val arg = parsePostfixChain(p.parsePrimaryExpr())
                    result = CstCall(result, null, listOf(arg), null, spanOf(result, arg))
                }
                else -> return result
            }
        }
    }

    /** 无括号调用实参判定：下个记号可开启一个 Primary 且非结构/中缀终结。 */
    private fun isNoParenArgStart(t: Token): Boolean = when (t.kind) {
        IDENTIFIER, INT_LITERAL, FLOAT_LITERAL, CHAR_LITERAL, STRING_LITERAL,
        RAW_STRING_LITERAL, STRING_START, LPAREN -> true
        LBRACE -> !p.suppressBlockCall
        KEYWORD -> t.text == "true" || t.text == "false" || t.text == "null" ||
            t.text == "this" || t.text == "super" || t.text == "throw"
        else -> false
    }

    /** 表达式语句 / 实参的安全终点：避免把结构记号当作调用实参。 */
    fun isExpressionEnd(): Boolean = when (p.current.kind) {
        NEWLINE, SEMICOLON, RBRACE, RPAREN, RBRACKET, COMMA, DOT, EOF, ARROW -> true
        else -> false
    }

    private fun spanOf(start: CstNode, end: CstNode) =
        yux.compiler.ast.SourceSpan(start.span.start, end.span.end)

    private fun spanOf(start: yux.compiler.lexer.Token, end: CstNode) =
        yux.compiler.ast.SourceSpan(start.position, end.span.end)
}
