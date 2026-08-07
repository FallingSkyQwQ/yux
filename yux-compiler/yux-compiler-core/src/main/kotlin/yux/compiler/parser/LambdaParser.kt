package yux.compiler.parser

import yux.compiler.ast.SourceSpan
import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind
import yux.compiler.lexer.TokenKind.ARROW
import yux.compiler.lexer.TokenKind.COMMA
import yux.compiler.lexer.TokenKind.IDENTIFIER
import yux.compiler.lexer.TokenKind.LPAREN
import yux.compiler.lexer.TokenKind.NEWLINE
import yux.compiler.lexer.TokenKind.RPAREN

/**
 * Lambda 解析（01-§7.4 / T-M2-6）。
 *
 * - 箭头 Lambda：`x -> x + 1`；多参括号形式 `(a, b) -> a + b` / `(a, b -> a + b)`；
 * - 块 Lambda（隐式 `it`）：`{ it.name }`，仅作调用实参或赋值右值（S-7.4.2）；
 * - 单参箭头必须写参数名；块 Lambda 的 `it` 绑定在 M3 名称解析阶段处理。
 */
internal class LambdaParser(private val p: Parser) {

    /** 解析 `x -> body`。调用前保证 current 为 IDENTIFIER 且下一个为 `->`。 */
    fun parseArrowLambda(): CstLambda {
        val param = p.expectIdent("lambda parameter")
        val arrow = p.expect(ARROW, "'->'")
        val body = p.pratt.parseExpression()
        return CstLambda(null, listOf(param), arrow, body, spanOf(param, body))
    }

    /** 解析括号形式 Lambda。调用前保证 [looksLikeParenLambda] 为 true。 */
    fun parseParenLambda(): CstLambda {
        val lparen = p.advance() // (
        val params = mutableListOf<Token>()
        p.skipNewlines()
        params += p.expectIdent("lambda parameter")
        p.skipNewlines()
        while (p.at(COMMA)) {
            p.advance()
            p.skipNewlines()
            params += p.expectIdent("lambda parameter")
            p.skipNewlines()
        }
        if (p.at(RPAREN)) p.advance() // `(a, b) -> body` 形式
        val arrow = p.expect(ARROW, "'->'")
        val body = p.pratt.parseExpression()
        if (p.at(RPAREN)) p.advance() // `(a, b -> body)` 形式
        return CstLambda(lparen, params, arrow, body, spanOf(lparen, body))
    }

    /** 判断 `(` 之后是否为括号形式 Lambda（含参数列表 + `->`）。 */
    fun looksLikeParenLambda(): Boolean {
        var i = 1
        // Skip newlines after opening paren
        while (p.peek(i).kind == NEWLINE) i++
        if (p.peek(i).kind != IDENTIFIER) return false
        i++
        // Skip newlines after first identifier
        while (p.peek(i).kind == NEWLINE) i++
        return when (p.peek(i).kind) {
            ARROW -> true
            COMMA -> {
                i++
                while (true) {
                    // Skip newlines after comma
                    while (p.peek(i).kind == NEWLINE) i++
                    if (p.peek(i).kind == IDENTIFIER) {
                        i++
                        while (p.peek(i).kind == NEWLINE) i++
                        if (p.peek(i).kind == COMMA) {
                            i++
                            continue
                        }
                    }
                    break
                }
                p.peek(i).kind == ARROW ||
                    (p.peek(i).kind == RPAREN && p.peek(i + 1).kind == ARROW)
            }
            else -> false
        }
    }

    /** 解析块 Lambda（隐式 `it`）：`{ it.name }`。 */
    fun parseBlockLambda(): CstBlockLambda {
        val block = p.parseBlock()
        return CstBlockLambda(block, block.span)
    }

    private fun spanOf(start: Token, end: CstNode): SourceSpan =
        SourceSpan(start.position, end.span.end)
}
