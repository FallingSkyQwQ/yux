package yux.compiler.parser

import yux.compiler.lexer.SourcePosition
import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind
import yux.compiler.lexer.TokenKind.ASSIGN
import yux.compiler.lexer.TokenKind.AT
import yux.compiler.lexer.TokenKind.COLON
import yux.compiler.lexer.TokenKind.COMMA
import yux.compiler.lexer.TokenKind.EOF
import yux.compiler.lexer.TokenKind.IDENTIFIER
import yux.compiler.lexer.TokenKind.KEYWORD
import yux.compiler.lexer.TokenKind.LBRACE
import yux.compiler.lexer.TokenKind.LPAREN
import yux.compiler.lexer.TokenKind.NEWLINE
import yux.compiler.lexer.TokenKind.RBRACE
import yux.compiler.lexer.TokenKind.RPAREN
import yux.compiler.lexer.TokenKind.SEMICOLON
import yux.compiler.lexer.TokenKind.SOFT_KEYWORD

/**
 * 符号表 + 消歧（01-§7.7 / 02-§6）。
 *
 * [PreSymbolTable] 由第一遍「声明收集」构建：遍历 token 流收集本文件声明的
 * 类型/函数/属性名，并预置 `yux.core` 基础类型名与 import 引入的类名（末段），
 * 供第二遍主体解析消歧 `Type(args)`（构造）vs `func(args)`（调用）。
 */
enum class SymKind { TYPE, FUNCTION, PROPERTY }

data class Sym(
    val name: String,
    val kind: SymKind,
    val topLevel: Boolean,
    val position: SourcePosition,
    /** 类型的类型参数个数（泛型元数，01-§4.3.2）；非类型恒为 0。 */
    val typeParamCount: Int = 0,
)

class PreSymbolTable {
    private val symbols = LinkedHashMap<String, Sym>()

    fun declare(sym: Sym) {
        // 后声明的（更内层作用域）覆盖先声明的；重复声明冲突检测属 M3。
        symbols[sym.name] = sym
    }

    fun lookup(name: String): Sym? = symbols[name]

    fun isType(name: String): Boolean = lookup(name)?.kind == SymKind.TYPE

    fun isFunction(name: String): Boolean = lookup(name)?.kind == SymKind.FUNCTION

    /** 已知类型的泛型元数；未知类型默认 0（非泛型）。 */
    fun typeParamCount(name: String): Int = lookup(name)?.typeParamCount ?: 0

    val size: Int get() = symbols.size
}

/**
 * 声明收集遍（02-§6.2 第一遍）：只解析「声明签名」，函数/块体按花括号平衡跳过。
 * 收集结果用于消歧，不承担语义检查（属 M3）。
 */
object DeclarationsCollector {

    /** `yux.core` 基础类型 + 常用集合（M3 起由 stdlib 提供，此处预置避免误判）。 */
    private val BUILTIN_TYPES = listOf(
        "Int", "Long", "Float", "Double", "Boolean", "Char", "String", "Byte",
        "Any", "Unit", "Nothing", "Range",
    )

    /** 已知泛型元数的内置集合类型（List/Map/Set 等，01-§10.2）。 */
    private val GENERIC_ARITY = mapOf(
        "List" to 1, "MutableList" to 1,
        "Map" to 2, "MutableMap" to 2,
        "Set" to 1, "MutableSet" to 1,
    )

    fun collect(tokens: List<Token>): PreSymbolTable {
        val table = PreSymbolTable()
        BUILTIN_TYPES.forEach { name ->
            table.declare(Sym(name, SymKind.TYPE, topLevel = true, position = SourcePosition(0, 1, 1)))
        }
        GENERIC_ARITY.forEach { (name, arity) ->
            table.declare(Sym(name, SymKind.TYPE, topLevel = true, position = SourcePosition(0, 1, 1), typeParamCount = arity))
        }
        val s = TokenScanner(tokens)
        scanTopLevel(s, table)
        return table
    }

    private fun scanTopLevel(s: TokenScanner, table: PreSymbolTable) {
        while (true) {
            s.skipNewlines()
            val t = s.peek()
            if (t.kind == EOF) return
            when {
                t.kind == KEYWORD && t.text == "package" || t.kind == KEYWORD && t.text == "import" -> s.skipLine()
                t.kind == AT -> skipAnnotation(s)
                t.kind == KEYWORD && t.text == "data" -> {
                    s.advance()
                    scanTypeNameAndBody(s, table)
                }
                t.kind == KEYWORD && t.text == "service" -> {
                    s.advance()
                    scanTypeNameAndBody(s, table)
                }
                t.kind == KEYWORD && t.text == "fun" -> {
                    s.advance()
                    scanFunction(s, table)
                }
                t.kind == KEYWORD && t.text == "async" && s.peek(1).isKeyword("fun") -> {
                    s.advance()
                    s.advance()
                    scanFunction(s, table)
                }
                t.kind == SOFT_KEYWORD && (t.text == "private" || t.text == "protected" || t.text == "override") -> {
                    s.advance()
                    when {
                        s.peek().kind == KEYWORD && s.peek().text == "data" -> {
                            s.advance()
                            scanTypeNameAndBody(s, table)
                        }
                        s.peek().kind == KEYWORD && s.peek().text == "service" -> {
                            s.advance()
                            scanTypeNameAndBody(s, table)
                        }
                        s.peek().kind == KEYWORD && s.peek().text == "fun" -> {
                            s.advance()
                            scanFunction(s, table)
                        }
                        s.peek().kind == IDENTIFIER -> scanIdentDecl(s, table)
                        else -> s.skipLine()
                    }
                }
                t.kind == IDENTIFIER -> scanIdentDecl(s, table)
                else -> s.skipLine()
            }
        }
    }

    /** 处理 `Identifier ...`：类声明（`X T {`）或属性声明（`X:`/`X=`）。 */
    private fun scanIdentDecl(s: TokenScanner, table: PreSymbolTable) {
        val name = s.advance()
        if (s.peek().kind == LBRACE) {
            table.declare(Sym(name.text, SymKind.TYPE, topLevel = true, name.position, 0))
            scanClassBody(s, table)
        } else if (s.peek().kind == COLON || s.peek().kind == ASSIGN) {
            table.declare(Sym(name.text, SymKind.PROPERTY, topLevel = true, name.position))
            skipProperty(s)
        } else if (s.peek().kind == IDENTIFIER) {
            // 类类型参数：`Player T {` → 类型参数在 `{` 之前
            var arity = 1
            s.advance()
            while (s.peek().kind == IDENTIFIER) {
                s.advance()
                arity++
            }
            if (s.peek().kind == LBRACE) {
                table.declare(Sym(name.text, SymKind.TYPE, topLevel = true, name.position, arity))
                scanClassBody(s, table)
            } else {
                table.declare(Sym(name.text, SymKind.PROPERTY, topLevel = true, name.position))
                s.skipLine()
            }
        } else {
            s.skipLine()
        }
    }

    /** `data X T extends ... { ... }` 之后：收集类型名（含泛型元数）并扫描类体成员。 */
    private fun scanTypeNameAndBody(s: TokenScanner, table: PreSymbolTable) {
        if (s.peek().kind != IDENTIFIER) {
            s.skipLine()
            return
        }
        val name = s.advance()
        var arity = 0
        while (s.peek().kind == IDENTIFIER) {
            s.advance() // 类型参数
            arity++
        }
        table.declare(Sym(name.text, SymKind.TYPE, topLevel = true, name.position, arity))
        scanExtendsImplements(s)
        if (s.peek().kind == LBRACE) scanClassBody(s, table) else s.skipLine()
    }

    private fun scanExtendsImplements(s: TokenScanner) {
        var done = false
        while (!done) {
            when {
                s.peek().isSoft("extends") || s.peek().isSoft("implements") -> {
                    s.advance()
                    skipType(s)
                }
                else -> done = true
            }
        }
    }

    /** 收集 `fun name T(params): Type { }` 的函数名。 */
    private fun scanFunction(s: TokenScanner, table: PreSymbolTable) {
        if (s.peek().kind != IDENTIFIER) {
            s.skipLine()
            return
        }
        val name = s.advance()
        table.declare(Sym(name.text, SymKind.FUNCTION, topLevel = true, name.position))
        while (s.peek().kind == IDENTIFIER) s.advance() // 类型参数
        if (s.peek().kind == LPAREN) s.skipBalanced(LPAREN, RPAREN)
        if (s.peek().kind == COLON) {
            s.advance()
            skipType(s)
        }
        when {
            s.peek().kind == LBRACE -> s.skipBalanced(LBRACE, RBRACE)
            s.peek().kind == ASSIGN -> s.skipLine()
            else -> s.skipLine()
        }
    }

    /** 属性签名：`name:Type = expr { get {} set {} }`。 */
    private fun skipProperty(s: TokenScanner) {
        if (s.peek().kind == COLON) {
            s.advance()
            skipType(s)
        }
        if (s.peek().kind == ASSIGN) s.skipLine()
        if (s.peek().kind == LBRACE) s.skipBalanced(LBRACE, RBRACE)
    }

    /** 跳过单个类型（含限定名、位置类型实参、可空标记、函数类型）。 */
    private fun skipType(s: TokenScanner) {
        if (s.peek().kind == LPAREN) {
            s.skipBalanced(LPAREN, RPAREN)
            if (s.peek().kind == TokenKind.ARROW) {
                s.advance()
                skipType(s)
            }
            return
        }
        if (s.peek().kind == IDENTIFIER) {
            s.advance()
            while (s.peek().kind == TokenKind.DOT && s.peek(1).kind == IDENTIFIER) {
                s.advance()
                s.advance()
            }
            while (s.peek().kind == IDENTIFIER) skipType(s)
        }
        if (s.peek().kind == TokenKind.QUESTION) s.advance()
    }

    private fun skipAnnotation(s: TokenScanner) {
        s.advance() // @
        while (s.peek().kind == IDENTIFIER || s.peek().kind == TokenKind.DOT) s.advance()
        if (s.peek().kind == LPAREN) s.skipBalanced(LPAREN, RPAREN)
    }

    /** 扫描类体：`{ ... }`，收集成员函数/属性名。 */
    private fun scanClassBody(s: TokenScanner, table: PreSymbolTable) {
        s.advance() // {
        var depth = 1
        while (depth > 0 && s.peek().kind != EOF) {
            s.skipNewlines()
            val t = s.peek()
            when {
                t.kind == RBRACE -> {
                    s.advance()
                    depth--
                }
                t.kind == LBRACE -> s.skipBalanced(LBRACE, RBRACE)
                t.kind == AT -> skipAnnotation(s)
                t.kind == SOFT_KEYWORD && (t.text == "private" || t.text == "protected" || t.text == "override") -> s.advance()
                t.kind == KEYWORD && t.text == "async" && s.peek(1).isKeyword("fun") -> {
                    s.advance()
                    s.advance()
                    scanFunction(s, table)
                }
                t.kind == KEYWORD && t.text == "fun" -> {
                    s.advance()
                    scanFunction(s, table)
                }
                t.kind == IDENTIFIER -> {
                    val name = s.advance()
                    when {
                        s.peek().kind == COLON || s.peek().kind == ASSIGN ->
                            table.declare(Sym(name.text, SymKind.PROPERTY, topLevel = false, name.position))
                        else -> table.declare(Sym(name.text, SymKind.FUNCTION, topLevel = false, name.position))
                    }
                    skipProperty(s)
                }
                else -> s.skipLine()
            }
        }
    }

    /** 轻量 token 游标。 */
    private class TokenScanner(val tokens: List<Token>) {
        var index = 0

        fun peek(rel: Int = 0): Token {
            val i = index + rel
            return if (i < tokens.size) tokens[i] else tokens.last()
        }

        fun advance(): Token = tokens[index].also { index++ }.let { if (index > tokens.size - 1) index = tokens.size - 1; it }

        fun isKeyword(word: String): Boolean = peek().kind == KEYWORD && peek().text == word

        fun skipNewlines() {
            while (peek().kind == NEWLINE || peek().kind == SEMICOLON) advance()
        }

        /** 跳到本行末（NEWLINE/`;`/EOF），忽略跨行花括号块。 */
        fun skipLine() {
            var depth = 0
            while (peek().kind != EOF) {
                when {
                    peek().kind == LBRACE -> {
                        depth++
                        advance()
                    }
                    peek().kind == RBRACE -> {
                        if (depth == 0) return
                        depth--
                        advance()
                    }
                    peek().kind == NEWLINE || peek().kind == SEMICOLON -> {
                        if (depth == 0) {
                            advance()
                            return
                        }
                        advance()
                    }
                    else -> advance()
                }
            }
        }

        /** 跳过平衡括号对，消费收尾符。 */
        fun skipBalanced(open: TokenKind, close: TokenKind) {
            if (peek().kind != open) return
            var depth = 0
            while (peek().kind != EOF) {
                when (peek().kind) {
                    open -> {
                        depth++
                        advance()
                    }
                    close -> {
                        depth--
                        advance()
                        if (depth == 0) return
                    }
                    else -> advance()
                }
            }
        }
    }
}
