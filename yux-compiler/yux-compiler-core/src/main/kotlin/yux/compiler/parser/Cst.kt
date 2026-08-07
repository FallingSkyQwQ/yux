package yux.compiler.parser

import yux.compiler.ast.SourceSpan
import yux.compiler.lexer.Token
import yux.compiler.lexer.TokenKind

/**
 * CST（Concrete Syntax Tree）节点（02-§5.2）：保留全部括号/换行/关键字记号，
 * 经 `CstToAst` 规约为 `ast` 包下的 AST 节点。
 *
 * 节点跨度 [span] 覆盖节点首个到末个记号的源码范围。
 */
sealed interface CstNode {
    val span: SourceSpan
}

/** 单个记号作为 CST 叶子。 */
data class CstToken(
    val token: Token,
    override val span: SourceSpan,
) : CstNode

// ── 程序与顶级声明（01-§3）────────────────────────────────────────────────────

class CstProgram(
    val decls: List<CstDecl>,
    override val span: SourceSpan,
) : CstNode

sealed interface CstDecl : CstNode

class CstPackageDecl(
    val pkgKw: Token,
    val name: List<Token>,
    override val span: SourceSpan,
) : CstDecl

class CstImportDecl(
    val importKw: Token,
    val name: List<Token>,
    val star: Token?, // `*`（import x.*）
    override val span: SourceSpan,
) : CstDecl

class CstClassDecl(
    val modifier: Token?, // private/protected
    val annotations: List<CstAnnotation>,
    val name: Token,
    val typeParams: List<Token>,
    val extendsKw: Token?,
    val extendsType: CstType?,
    val implementsKw: Token?,
    val implements: List<CstType>,
    val body: CstClassBody,
    override val span: SourceSpan,
) : CstDecl

class CstDataClassDecl(
    val dataKw: Token,
    val modifier: Token?,
    val annotations: List<CstAnnotation>,
    val name: Token,
    val typeParams: List<Token>,
    val extendsKw: Token?,
    val extendsType: CstType?,
    val implementsKw: Token?,
    val implements: List<CstType>,
    val body: CstClassBody,
    override val span: SourceSpan,
) : CstDecl

class CstServiceDecl(
    val serviceKw: Token,
    val annotations: List<CstAnnotation>,
    val name: Token,
    val typeParams: List<Token>,
    val body: CstClassBody,
    override val span: SourceSpan,
) : CstDecl

/** 类体（ClassBody）：`{ member* }`。 */
class CstClassBody(
    val lbrace: Token,
    val members: List<CstClassMember>,
    val rbrace: Token,
    override val span: SourceSpan,
) : CstNode

sealed interface CstClassMember : CstNode

class CstFunctionDecl(
    val annotations: List<CstAnnotation>,
    val asyncKw: Token?, // async fun
    val overrideKw: Token?,
    val modifier: Token?, // private/protected
    val funKw: Token?, // `fun`（service 成员可省略，01-§5.4 示例）
    val name: Token,
    val typeParams: List<Token>,
    val params: List<CstParameter>,
    val colonKw: Token?, // 返回类型前的 `:`
    val returnType: CstType?,
    val body: CstFunctionBody,
    override val span: SourceSpan,
) : CstClassMember, CstDecl

sealed interface CstFunctionBody : CstNode

/** 块体。 */
class CstBlockBody(
    val block: CstBlock,
    override val span: SourceSpan,
) : CstFunctionBody

/** `= expr` 表达式体。 */
class CstExpressionBody(
    val assignKw: Token,
    val expr: CstExpr,
    override val span: SourceSpan,
) : CstFunctionBody

class CstPropertyDecl(
    val annotations: List<CstAnnotation>,
    val modifier: Token?,
    val name: Token,
    val colonKw: Token?, // 类型前的 `:`
    val type: CstType?,
    val assignKw: Token?, // `= expr`
    val initializer: CstExpr?,
    val accessors: List<CstAccessor>, // `{ get {} set {} }`
    override val span: SourceSpan,
) : CstClassMember, CstDecl

class CstAccessor(
    val kw: Token, // get / set
    val body: CstBlock,
    override val span: SourceSpan,
) : CstNode

class CstParameter(
    val annotations: List<CstAnnotation>,
    val name: Token,
    val colonKw: Token,
    val type: CstType,
    val assignKw: Token?, // 默认值 `= expr`
    val defaultValue: CstExpr?,
    override val span: SourceSpan,
) : CstNode

class CstAnnotation(
    val atKw: Token,
    val name: List<Token>, // 限定名 `a.b.C`
    val lparen: Token?,
    val args: List<CstAnnotationArg>,
    val rparen: Token?,
    override val span: SourceSpan,
) : CstNode

class CstAnnotationArg(
    val name: Token,
    val assignKw: Token,
    val value: CstExpr,
    override val span: SourceSpan,
) : CstNode

/** 初始化块（ClassMember 的 BlockDecl）。 */
class CstInitBlock(
    val block: CstBlock,
    override val span: SourceSpan,
) : CstClassMember

// ── 类型（01-§4）──────────────────────────────────────────────────────────────

sealed interface CstType : CstNode

/** 命名类型 `A.B C D?`。 */
class CstNamedType(
    val segments: List<Token>,
    val typeArgs: List<CstType>,
    val nullableKw: Token?, // `?`
    override val span: SourceSpan,
) : CstType

/** 函数类型 `(Int, String) -> Boolean`。 */
class CstFunctionType(
    val lparen: Token,
    val params: List<CstType>,
    val rparen: Token,
    val arrow: Token,
    val ret: CstType,
    override val span: SourceSpan,
) : CstType

// ── 语句（01-§6）──────────────────────────────────────────────────────────────

sealed interface CstStmt : CstNode

class CstBlock(
    val lbrace: Token,
    val statements: List<CstStmt>,
    val rbrace: Token,
    override val span: SourceSpan,
) : CstStmt

/** 变量声明（含推断类型；`age:Int` 可无初始值）。 */
class CstVarDeclStmt(
    val name: Token,
    val colonKw: Token?,
    val type: CstType?,
    val assignKw: Token?, // `=`
    val initializer: CstExpr?,
    override val span: SourceSpan,
) : CstStmt

/** 赋值语句 `x = e` / `x += e`。 */
class CstAssignStmt(
    val target: CstExpr,
    val op: Token,
    val value: CstExpr,
    override val span: SourceSpan,
) : CstStmt

class CstExprStmt(
    val expr: CstExpr,
    override val span: SourceSpan,
) : CstStmt

class CstIfStmt(
    val ifKw: Token,
    val condition: CstExpr,
    val thenBranch: CstStmt,
    val elseKw: Token?,
    val elseBranch: CstStmt?,
    override val span: SourceSpan,
) : CstStmt

class CstWhenStmt(
    val whenKw: Token,
    val subject: CstExpr,
    val lbrace: Token,
    val branches: List<CstWhenBranch>,
    val rbrace: Token,
    override val span: SourceSpan,
) : CstStmt

class CstWhenBranch(
    val condition: CstWhenCondition,
    val arrow: Token,
    val body: CstStmt,
    override val span: SourceSpan,
) : CstNode

sealed interface CstWhenCondition : CstNode

/** `is T` 分支条件。 */
class CstIsWhenCondition(
    val isKw: Token,
    val type: CstType,
    override val span: SourceSpan,
) : CstWhenCondition

/** `else` 分支。 */
class CstElseWhenCondition(
    val elseKw: Token,
    override val span: SourceSpan,
) : CstWhenCondition

/** 表达式条件。 */
class CstExprWhenCondition(
    val expr: CstExpr,
    override val span: SourceSpan,
) : CstWhenCondition

class CstForStmt(
    val forKw: Token,
    val varName: Token,
    val inKw: Token,
    val iterable: CstExpr,
    val body: CstStmt,
    override val span: SourceSpan,
) : CstStmt

class CstWhileStmt(
    val whileKw: Token,
    val condition: CstExpr,
    val body: CstStmt,
    override val span: SourceSpan,
) : CstStmt

class CstReturnStmt(
    val returnKw: Token,
    val value: CstExpr?,
    override val span: SourceSpan,
) : CstStmt

class CstBreakStmt(
    val kw: Token,
    override val span: SourceSpan,
) : CstStmt

class CstContinueStmt(
    val kw: Token,
    override val span: SourceSpan,
) : CstStmt

class CstTryStmt(
    val tryKw: Token,
    val body: CstStmt,
    val catches: List<CstCatchClause>,
    val finallyKw: Token?,
    val finallyBody: CstStmt?,
    override val span: SourceSpan,
) : CstStmt

class CstCatchClause(
    val catchKw: Token,
    val paramName: Token,
    val colonKw: Token?,
    val type: CstType?,
    val body: CstStmt,
    override val span: SourceSpan,
) : CstNode

class CstAsyncStmt(
    val kw: Token,
    val block: CstBlock,
    override val span: SourceSpan,
) : CstStmt

class CstParallelStmt(
    val kw: Token,
    val block: CstBlock,
    override val span: SourceSpan,
) : CstStmt

class CstUnsafeStmt(
    val kw: Token,
    val block: CstBlock,
    override val span: SourceSpan,
) : CstStmt

// ── 表达式（01-§7）────────────────────────────────────────────────────────────

sealed interface CstExpr : CstNode

class CstIntLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstFloatLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstCharLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstBoolLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstNullLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstRawStringLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstStringLiteral(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstStringText(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

/** 插值串：parts 由 CstStringText 与其余 CstExpr 组成。 */
class CstStringTemplate(
    val startKw: Token, // STRING_START
    val parts: List<CstExpr>,
    val endKw: Token, // STRING_END
    override val span: SourceSpan,
) : CstExpr

class CstIdentifier(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstThis(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

class CstSuper(
    val token: Token,
    override val span: SourceSpan,
) : CstExpr

/** 类型引用（表达式上下文，如静态成员基）。 */
class CstTypeReference(
    val type: CstType,
    override val span: SourceSpan,
) : CstExpr

class CstMemberAccess(
    val receiver: CstExpr,
    val dot: Token,
    val name: Token,
    override val span: SourceSpan,
) : CstExpr

/** 调用（含无括号单参调用，此时 [lparen]/[rparen] 为空）。 */
class CstCall(
    val callee: CstExpr,
    val lparen: Token?,
    val args: List<CstExpr>,
    val rparen: Token?,
    override val span: SourceSpan,
) : CstExpr

/** 构造调用 `Type(args)` / `Type TypeArg(args)`。 */
class CstTypeCall(
    val type: CstType,
    val lparen: Token,
    val args: List<CstExpr>,
    val rparen: Token,
    override val span: SourceSpan,
) : CstExpr

class CstBinary(
    val left: CstExpr,
    val op: Token,
    val right: CstExpr,
    override val span: SourceSpan,
) : CstExpr

class CstUnary(
    val op: Token,
    val operand: CstExpr,
    override val span: SourceSpan,
) : CstExpr

/** 显式 Lambda：`x -> body` / `(a, b) -> body`。 */
class CstLambda(
    val lparen: Token?,
    val params: List<Token>,
    val arrow: Token,
    val body: CstExpr,
    override val span: SourceSpan,
) : CstExpr

/** 块 Lambda（隐式 `it`）：`{ it.name }`。 */
class CstBlockLambda(
    val block: CstBlock,
    override val span: SourceSpan,
) : CstExpr

class CstParen(
    val lparen: Token,
    val expr: CstExpr,
    val rparen: Token,
    override val span: SourceSpan,
) : CstExpr

class CstThrow(
    val throwKw: Token,
    val expr: CstExpr,
    override val span: SourceSpan,
) : CstExpr

class CstIsExpr(
    val expr: CstExpr,
    val isKw: Token,
    val type: CstType,
    override val span: SourceSpan,
) : CstExpr

class CstAsExpr(
    val expr: CstExpr,
    val asKw: Token,
    val type: CstType,
    override val span: SourceSpan,
) : CstExpr

class CstNullable(
    val expr: CstExpr,
    val questionKw: Token,
    override val span: SourceSpan,
) : CstExpr

class CstRangeExpr(
    val from: CstExpr,
    val op: Token, // `..`
    val to: CstExpr,
    override val span: SourceSpan,
) : CstExpr

/** 表达式级赋值（右值上下文，如 `a.b = x`）。 */
class CstAssignmentExpr(
    val target: CstExpr,
    val op: Token,
    val value: CstExpr,
    override val span: SourceSpan,
) : CstExpr

// ── 工具 ──────────────────────────────────────────────────────────────────────

/** 记号范围：start 到 end（含两端的偏移）。 */
internal fun spanOf(start: Token, end: Token): SourceSpan =
    SourceSpan(start.position, end.position)

/** 单个记号的跨度。 */
internal fun spanOf(token: Token): SourceSpan =
    SourceSpan(token.position, token.position)

/** 以两个跨度构造合并范围（取各自起止偏移）。 */
internal fun spanOf(start: SourceSpan, end: SourceSpan): SourceSpan =
    SourceSpan(start.start, end.end)

/** 记号到 CST 节点。 */
internal fun spanOf(start: Token, end: CstNode): SourceSpan =
    SourceSpan(start.position, end.span.end)

/** CST 节点到记号。 */
internal fun spanOf(start: CstNode, end: Token): SourceSpan =
    SourceSpan(start.span.start, end.position)

/** CST 节点到 CST 节点。 */
internal fun spanOf(start: CstNode, end: CstNode): SourceSpan =
    SourceSpan(start.span.start, end.span.end)

/** 记号是否属于某关键字。 */
internal fun Token.isKeyword(word: String): Boolean =
    kind == TokenKind.KEYWORD && text == word

/** 记号是否属于某软关键字。 */
internal fun Token.isSoft(word: String): Boolean =
    kind == TokenKind.SOFT_KEYWORD && text == word
