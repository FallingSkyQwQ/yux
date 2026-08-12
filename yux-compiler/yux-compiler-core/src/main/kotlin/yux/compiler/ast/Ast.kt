package yux.compiler.ast

/**
 * AST 节点基类（02-§5.2）：CstToAst 规约产物，节点携带源码范围 [span]。
 *
 * 本层为编译器后续阶段（M3 Sema / M4 IR）的稳定输入，故去除了 CST 中的
 * 括号/换行/关键字等冗余记号，只保留语义结构。
 */
sealed interface YxNode {
    val span: SourceSpan
}

// ── 声明（01-§3, §5）──────────────────────────────────────────────────────────

sealed interface YxDecl : YxNode

/** `package com.example` */
class YxPackage(
    val name: String,
    override val span: SourceSpan,
) : YxDecl

/** `import java.util.UUID` / `import org.bukkit.*` */
class YxImport(
    val qualifiedName: List<String>,
    val star: Boolean,
    override val span: SourceSpan,
) : YxDecl

/** 注解（01-§8.2）。 */
class YxAnnotation(
    val qualifiedName: List<String>,
    val args: List<YxAnnotationArg>,
    override val span: SourceSpan,
) : YxNode

class YxAnnotationArg(
    val name: String,
    val value: YxExpr,
    override val span: SourceSpan,
) : YxNode

/** 类型参数声明（01-§4.3.3）：`data Result T`。 */
class YxTypeParam(
    val name: String,
    override val span: SourceSpan,
) : YxNode

enum class YxVisibility { PUBLIC, PRIVATE, PROTECTED }

/** 函数参数（01-§5.5）。 */
class YxParameter(
    val name: String,
    val type: YxType,
    val defaultValue: YxExpr?,
    val annotations: List<YxAnnotation>,
    override val span: SourceSpan,
) : YxNode

/** 类声明（01-§5.1）：不写 `class` 关键字，`Identifier { ... }`。 */
class YxClass(
    val name: String,
    val typeParams: List<YxTypeParam>,
    val superType: YxType?,
    val interfaces: List<YxType>,
    val members: List<YxClassMember>,
    val visibility: YxVisibility,
    val annotations: List<YxAnnotation>,
    /** `sealed` 软关键字修饰（T-M12）：密封类不可直接实例化；直接子类须同文件且同为 sealed。 */
    val isSealed: Boolean = false,
    override val span: SourceSpan,
) : YxDecl

/** data 类（01-§5.3）。成员仅允许属性（S-5.3.1）；函数/初始化块保留供 sema 报 E0011。 */
class YxDataClass(
    val name: String,
    val typeParams: List<YxTypeParam>,
    val superType: YxType?,
    val interfaces: List<YxType>,
    val members: List<YxClassMember>,
    val visibility: YxVisibility,
    val annotations: List<YxAnnotation>,
    /** `sealed` 软关键字修饰（T-M12）：语义同 [YxClass.isSealed]。 */
    val isSealed: Boolean = false,
    override val span: SourceSpan,
) : YxDecl {
    val properties: List<YxProperty> get() = members.filterIsInstance<YxProperty>()
}

/** service 声明（01-§5.4）。 */
class YxService(
    val name: String,
    val typeParams: List<YxTypeParam>,
    val members: List<YxClassMember>,
    val annotations: List<YxAnnotation>,
    override val span: SourceSpan,
) : YxDecl

/**
 * 扩展声明（02-§10.1 / T-M6）：由编译器插件的 [ExtensionParser] 在解析钩子处产出，
 * 携带插件 id、关键字与不透明载荷 [payload]；必须在 PluginLowering 前全部下沉为普通 AST。
 */
class YxExtensionDecl(
    val keyword: String,
    val pluginId: String,
    val payload: Any?,
    override val span: SourceSpan,
) : YxDecl

/** 类成员：属性、函数、初始化块（ClassMember ::= PropertyDecl | FunctionDecl | BlockDecl）。 */
sealed interface YxClassMember : YxNode

/** 属性（01-§5.2）。顶级属性亦用本节点（[isTopLevel]）。 */
class YxProperty(
    val name: String,
    val type: YxType?,
    val initializer: YxExpr?,
    val accessors: List<YxAccessor>,
    val annotations: List<YxAnnotation>,
    val isTopLevel: Boolean,
    val visibility: YxVisibility = YxVisibility.PUBLIC,
    override val span: SourceSpan,
) : YxClassMember,
    YxDecl

enum class YxAccessorKind { GET, SET }

/** 自定义访问器（01-§5.2）：`get { ... }` / `set { ... }`。 */
class YxAccessor(
    val kind: YxAccessorKind,
    val body: YxBlock,
    override val span: SourceSpan,
) : YxNode

/** 初始化块（ClassMember 的 BlockDecl 情形）。 */
class YxInitBlock(
    val body: YxBlock,
    override val span: SourceSpan,
) : YxClassMember

/** 函数声明（01-§5.5）。 */
class YxFunction(
    val name: String,
    val typeParams: List<YxTypeParam>,
    val params: List<YxParameter>,
    val returnType: YxType?,
    val body: YxFunctionBody,
    val isAsync: Boolean,
    val isOverride: Boolean,
    val visibility: YxVisibility,
    val annotations: List<YxAnnotation>,
    /** 扩展函数接收者类型（M9：`fun <Receiver>.<name>`，null=普通函数）。 */
    val receiver: YxType? = null,
    override val span: SourceSpan,
) : YxClassMember,
    YxDecl

sealed interface YxFunctionBody : YxNode {
    /** `= expr` 单表达式体（推断返回类型，S-5.5.2）。 */
    class YxExpressionBody(
        val expr: YxExpr,
        override val span: SourceSpan,
    ) : YxFunctionBody

    /** 块体。 */
    class YxBlockBody(
        val block: YxBlock,
        override val span: SourceSpan,
    ) : YxFunctionBody
}

/** service 成员即属性或函数（复用 [YxProperty] / [YxFunction]）。 */

// ── 类型（01-§4）──────────────────────────────────────────────────────────────

sealed interface YxType : YxNode

/** 命名类型 `A.B C D?`（含可空标记与位置类型实参）。 */
class YxNamedType(
    val segments: List<String>,
    val typeArgs: List<YxType>,
    val nullable: Boolean,
    override val span: SourceSpan,
) : YxType

/** 函数类型 `(Int, String) -> Boolean`（01-§4.4）。 */
class YxFunctionType(
    val params: List<YxType>,
    val ret: YxType,
    override val span: SourceSpan,
) : YxType

// ── 语句（01-§6）──────────────────────────────────────────────────────────────

sealed interface YxStmt : YxNode

/** 块（Block）。 */
class YxBlock(
    val statements: List<YxStmt>,
    override val span: SourceSpan,
) : YxStmt

/** 变量声明（01-§6.1）：`name:Type = expr` / `name = expr`。 */
class YxVarDecl(
    val name: String,
    val type: YxType?,
    val initializer: YxExpr?,
    override val span: SourceSpan,
) : YxStmt

/** 赋值（01-§6.1）：`= += -= *= /= %= ..=`。语句与表达式两用。 */
class YxAssign(
    val target: YxExpr,
    val op: String,
    val value: YxExpr,
    override val span: SourceSpan,
) : YxStmt,
    YxExpr

/** 表达式语句。 */
class YxExprStmt(
    val expr: YxExpr,
    override val span: SourceSpan,
) : YxStmt

/** if / else（01-§6.2）。 */
class YxIf(
    val condition: YxExpr,
    val thenBranch: YxStmt,
    val elseBranch: YxStmt?,
    override val span: SourceSpan,
) : YxStmt

/** when（01-§6.3）。subject 可为空（`when { cond -> }` 无 subject 形式，条件为布尔表达式）。 */
class YxWhen(
    val subject: YxExpr?,
    val branches: List<YxWhenBranch>,
    override val span: SourceSpan,
) : YxStmt

class YxWhenBranch(
    val condition: YxWhenCondition,
    val body: YxStmt,
    override val span: SourceSpan,
) : YxNode

/** if 表达式（M9）：`if <cond> then <thenExpr> else <elseExpr>`（01-§7 扩展 / 04-§7 用法）。 */
class YxIfExpr(
    val condition: YxExpr,
    val thenExpr: YxExpr,
    val elseExpr: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** when 表达式（T-M12，01-§7 扩展）：`when <subject> { cond -> expr ... [else -> expr] }`。
 *  与 if 表达式一致产生值；分支体为单个表达式。非密封 subject 要求 else（S-6.2.2 补充）。
 *  subject 可为空（`when { cond -> expr ... }` 无 subject 形式，条件为布尔表达式，必须 else）。 */
class YxWhenExpr(
    val subject: YxExpr?,
    val branches: List<YxWhenExprBranch>,
    override val span: SourceSpan,
) : YxExpr

class YxWhenExprBranch(
    val condition: YxWhenCondition,
    val body: YxExpr,
    override val span: SourceSpan,
) : YxNode

/** 索引访问（M9）：`expr[index]` 读（04-§7），赋值目标为 `YxAssign(target=YxIndexExpr)`。 */
class YxIndexExpr(
    val base: YxExpr,
    val index: YxExpr,
    override val span: SourceSpan,
) : YxExpr

sealed interface YxWhenCondition : YxNode

/** `is T` 分支（S-6.3.1 智能转型）。 */
class YxIsCondition(
    val type: YxType,
    override val span: SourceSpan,
) : YxWhenCondition

/** `else` 分支。 */
class YxElseCondition(
    override val span: SourceSpan,
) : YxWhenCondition

/** 普通表达式条件。 */
class YxExprCondition(
    val expr: YxExpr,
    override val span: SourceSpan,
) : YxWhenCondition

/** for（01-§6.4）。 */
class YxFor(
    val varName: String,
    val iterable: YxExpr,
    val body: YxStmt,
    override val span: SourceSpan,
) : YxStmt

/** while（01-§6.4）。 */
class YxWhile(
    val condition: YxExpr,
    val body: YxStmt,
    override val span: SourceSpan,
) : YxStmt

class YxReturn(
    val value: YxExpr?,
    override val span: SourceSpan,
) : YxStmt

class YxBreak(
    override val span: SourceSpan,
) : YxStmt

class YxContinue(
    override val span: SourceSpan,
) : YxStmt

/** try / catch / finally（01-§6.6）。 */
class YxTry(
    val body: YxStmt,
    val catches: List<YxCatchClause>,
    val finallyBody: YxStmt?,
    override val span: SourceSpan,
) : YxStmt

class YxCatchClause(
    val paramName: String,
    val type: YxType?,
    val body: YxStmt,
    override val span: SourceSpan,
) : YxNode

/** `async { }`（01-§6.7）。 */
class YxAsyncBlock(
    val body: YxBlock,
    override val span: SourceSpan,
) : YxStmt

/** `parallel { }`（01-§6.7）。 */
class YxParallelBlock(
    val body: YxBlock,
    override val span: SourceSpan,
) : YxStmt

/** `unsafe { }`（01-§8.6.3）。 */
class YxUnsafeBlock(
    val body: YxBlock,
    override val span: SourceSpan,
) : YxStmt

// ── 表达式（01-§7）────────────────────────────────────────────────────────────

sealed interface YxExpr : YxNode

class YxIntLiteral(
    /** 原始文本（含后缀），如 `18`、`0x1F`、`1_000L`；数值解析在 M3 进行。 */
    val text: String,
    override val span: SourceSpan,
) : YxExpr

class YxFloatLiteral(
    val text: String,
    override val span: SourceSpan,
) : YxExpr

class YxCharLiteral(
    val text: String,
    override val span: SourceSpan,
) : YxExpr

class YxBoolLiteral(
    val value: Boolean,
    override val span: SourceSpan,
) : YxExpr

class YxNullLiteral(
    override val span: SourceSpan,
) : YxExpr

class YxRawStringLiteral(
    val text: String,
    override val span: SourceSpan,
) : YxExpr

/** 普通字符串字面量（无插值）。 */
class YxStringLiteral(
    val text: String,
    override val span: SourceSpan,
) : YxExpr

/** 插值串的纯文本段。 */
class YxStringText(
    val text: String,
    override val span: SourceSpan,
) : YxExpr

/** 插值串（02-§4.3 / S-7.6.1）：parts 由 YxStringText 与其余 YxExpr 交替组成。 */
class YxStringTemplate(
    val parts: List<YxExpr>,
    override val span: SourceSpan,
) : YxExpr

/** 变量/函数引用。 */
class YxIdentifier(
    val name: String,
    override val span: SourceSpan,
) : YxExpr

class YxThis(
    override val span: SourceSpan,
) : YxExpr

class YxSuper(
    override val span: SourceSpan,
) : YxExpr

/**
 * 类型位置的引用（表达式上下文）：用于静态成员访问（`System.currentTimeMillis`）
 * 或类型本身作为值的场景（01-§7.7 消歧规则 3）。
 */
class YxTypeReference(
    val type: YxType,
    override val span: SourceSpan,
) : YxExpr

/** 成员访问 `a.b`（属性访问；JavaBean getX() 映射在 M5）。 */
class YxMemberAccess(
    val receiver: YxExpr,
    val name: String,
    override val span: SourceSpan,
) : YxExpr

/**
 * 调用（01-§7.2）：`callee(a, b)` 或无括号单参 `callee arg`（[noParen]=true）。
 */
class YxCall(
    val callee: YxExpr,
    val args: List<YxExpr>,
    val noParen: Boolean,
    override val span: SourceSpan,
) : YxExpr

/** 构造调用（01-§7.3）：`Type(args)` / `Type TypeArg(args)`。 */
class YxTypeCall(
    val type: YxType,
    val args: List<YxExpr>,
    override val span: SourceSpan,
) : YxExpr

/** 二元运算（01-§7.5）。 */
class YxBinary(
    val op: String,
    val left: YxExpr,
    val right: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** 一元运算 `!x` / `-x`。 */
class YxUnary(
    val op: String,
    val operand: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** 显式 Lambda（01-§7.4）：`x -> x + 1` / `(a, b) -> a + b`。 */
class YxLambda(
    val params: List<String>,
    val body: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** 块 Lambda（隐式 `it`，01-§7.4.2）：`{ it.name }`。 */
class YxBlockLambda(
    val body: YxBlock,
    override val span: SourceSpan,
) : YxExpr

/** 括号分组 `(expr)`。 */
class YxParen(
    val expr: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** `throw expr`（01-§6.6.3）。 */
class YxThrow(
    val expr: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/**
 * `await <expr>`（S-8.4.2，T-M14）：async 上下文内的挂起点。
 * 软关键字：仅 async fun / `async { }` 体内作为一元前缀运算符（`await(x)` 调用形兼容）；
 * 其他位置 `await` 保持普通标识符语义。
 */
class YxAwait(
    val operand: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** `is T` 类型检查（01-§7.5.3）。 */
class YxIs(
    val expr: YxExpr,
    val type: YxType,
    override val span: SourceSpan,
) : YxExpr

/** `as T` 强制转换（01-§7.5.3）。 */
class YxAs(
    val expr: YxExpr,
    val type: YxType,
    override val span: SourceSpan,
) : YxExpr

/** 后缀可空标记 `expr?`（01-§7.1 Postfix）。 */
class YxNullable(
    val expr: YxExpr,
    override val span: SourceSpan,
) : YxExpr

/** 区间 `a..b`（01-§6.4.2）。 */
class YxRange(
    val from: YxExpr,
    val to: YxExpr,
    override val span: SourceSpan,
) : YxExpr
