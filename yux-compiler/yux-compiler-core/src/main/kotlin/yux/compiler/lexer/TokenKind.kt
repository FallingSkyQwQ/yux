package yux.compiler.lexer

/**
 * Token 种类（02-§4.2）。符号类记号与 01-§2.6 运算符表一一对应。
 */
enum class TokenKind {
    // 标识符与字面量
    IDENTIFIER,
    INT_LITERAL,
    FLOAT_LITERAL,
    CHAR_LITERAL,

    // 字符串（普通字符串为单个 STRING_LITERAL；插值串拆为 START/TEXT/…/END）
    STRING_LITERAL,
    RAW_STRING_LITERAL,
    STRING_START,
    STRING_TEXT,
    STRING_END,
    INTERPOLATION_START,

    // 关键字 / 软关键字
    KEYWORD,
    SOFT_KEYWORD,

    // 运算符与分隔符（01-§2.6）
    DOT,
    COMMA,
    COLON,
    SEMICOLON,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    ASSIGN,
    EQ,
    NEQ,
    LT,
    GT,
    LE,
    GE,
    AND,
    OR,
    NOT,
    PLUS_ASSIGN,
    MINUS_ASSIGN,
    STAR_ASSIGN,
    SLASH_ASSIGN,
    PERCENT_ASSIGN,
    RANGE,
    ARROW,

    // 注解与可空标记（§8.2 / §4.2）
    AT,
    QUESTION,

    // 结构
    NEWLINE,
    EOF,
}

/** 关键字表（01-§2.4）：30 个基础关键字 + 5 个软关键字。 */
object Keywords {
    val BASE: Set<String> = setOf(
        "fun", "data", "service", "async", "parallel", "try", "catch", "finally",
        "if", "else", "when", "for", "while", "return",
        "import", "package", "new", "unsafe", "native", "stack",
        "in", "is", "as", "this", "super",
        "break", "continue", "true", "false", "null", "throw",
    )

    val SOFT: Set<String> = setOf(
        "extends", "implements", "override", "private", "protected",
        "then", // M9（T-M9-1）：if 表达式 `if c then a else b`（01-§7 扩展，04-§7 用法）
    )
}

/** 运算符表（01-§2.6 + 注解 `@` + 可空 `?`）。 */
object Symbols {
    val twoChar: Map<String, TokenKind> = mapOf(
        "==" to TokenKind.EQ,
        "!=" to TokenKind.NEQ,
        "<=" to TokenKind.LE,
        ">=" to TokenKind.GE,
        "&&" to TokenKind.AND,
        "||" to TokenKind.OR,
        "+=" to TokenKind.PLUS_ASSIGN,
        "-=" to TokenKind.MINUS_ASSIGN,
        "*=" to TokenKind.STAR_ASSIGN,
        "/=" to TokenKind.SLASH_ASSIGN,
        "%=" to TokenKind.PERCENT_ASSIGN,
        ".." to TokenKind.RANGE,
        "->" to TokenKind.ARROW,
    )

    val single: Map<Char, TokenKind> = mapOf(
        '.' to TokenKind.DOT,
        ',' to TokenKind.COMMA,
        ':' to TokenKind.COLON,
        ';' to TokenKind.SEMICOLON,
        '(' to TokenKind.LPAREN,
        ')' to TokenKind.RPAREN,
        '[' to TokenKind.LBRACKET,
        ']' to TokenKind.RBRACKET,
        '{' to TokenKind.LBRACE,
        '}' to TokenKind.RBRACE,
        '+' to TokenKind.PLUS,
        '-' to TokenKind.MINUS,
        '*' to TokenKind.STAR,
        '/' to TokenKind.SLASH,
        '%' to TokenKind.PERCENT,
        '=' to TokenKind.ASSIGN,
        '!' to TokenKind.NOT,
        '<' to TokenKind.LT,
        '>' to TokenKind.GT,
        '@' to TokenKind.AT,
        '?' to TokenKind.QUESTION,
    )
}
