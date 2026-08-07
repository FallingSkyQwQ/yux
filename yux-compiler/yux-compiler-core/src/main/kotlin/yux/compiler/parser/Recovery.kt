package yux.compiler.parser

import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind

/**
 * 解析错误恢复（02-§12.3 / T-M2-8）：同步恢复——跳过到安全位置
 * （NEWLINE / `;` / `}` / EOF）后继续，收集多个诊断而不中断编译。
 */
object Recovery {

    /** 跳过到本语句结束（NEWLINE/`;`/`}`/EOF），供错误语句后的同步恢复。 */
    fun skipToStatementEnd(tokens: List<Token>, index: Int): Int {
        var i = index
        while (i < tokens.size) {
            when (tokens[i].kind) {
                TokenKind.NEWLINE, TokenKind.SEMICOLON, TokenKind.EOF -> return i
                TokenKind.RBRACE -> return i
                else -> i++
            }
        }
        return i
    }

    /** 跳过到 NEWLINE / `;` / EOF（不跳过 `}`，供块内恢复）。 */
    fun skipToLineEnd(tokens: List<Token>, index: Int): Int {
        var i = index
        while (i < tokens.size) {
            when (tokens[i].kind) {
                TokenKind.NEWLINE, TokenKind.SEMICOLON, TokenKind.EOF -> return i
                else -> i++
            }
        }
        return i
    }
}
