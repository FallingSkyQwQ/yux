# Yux 语言规范 v0.2（完整语法规范）

> 定位：对标《Java Language Specification》的 Yux 完整规范。
> 前置：`Design.md`（v0.1 设计稿）与 `00-总览.md`（ADR-01~14）。
> 本文为编译器（`02`）与 SDK（`03`）的实现依据。语义规则编号 `S-xx`，供后续文档引用。

## 目录

1. 绪论
2. 词法结构（Lexical Grammar）
3. 语法总览（Syntactic Grammar）
4. 类型系统
5. 声明与对象
6. 语句
7. 表达式
8. 特性语义（空安全 / 序列化 / 并发 / 互操作）
9. 编译器插件扩展点
10. 标准库概览

---

# 1. 绪论

## 1.1 设计目标

- **极简**：约 30 个基础关键字；不写 `class` 即得类。
- **JVM First**：与 Java/Kotlin 100% 互操作，注释、注解、异常全部沿用 JVM 模型。
- **可预测**：局部双向类型推断，错误信息直观，适合手写递归下降解析。
- **可扩展**：通过编译器插件 API 注入 `plugin`/`event`/`command` 等领域语法（见第 9 章）。

## 1.2 约定

- EBNF 记号见 `00-总览.md` §5。
- 新行（NEWLINE）是语句终结符；`;` 是可选替代终结符（支持单行多语句）。
- 每个语法产式后附语义规则 `S-xx` 与示例。

---

# 2. 词法结构

## 2.1 字符集与空白

- 源码为 UTF-8。字符集为 Unicode。
- 空白符：空格（U+0020）、水平制表（U+0009）、换行（NEWLINE）、回车等。
- 词法器将换行输出为 `NEWLINE` 记号，供语法层作语句终结符（多个连续换行折叠为一个）。

## 2.2 注释

```
LineComment   ::= '//' {Char} NEWLINE
BlockComment  ::= '/*' {Char} '*/'         // 可嵌套
DocComment    ::= '/**' {Char} '*/'         // 生成文档，语义同 BlockComment
```

- 注释等价于空白；`BlockComment` 与 `DocComment` 支持嵌套。
- 文档注释保留在 AST 中，供编译器生成 Javadoc/KDoc 等价物。

## 2.3 标识符

```
Letter        ::= 'a'..'z' | 'A'..'Z' | '_' | 任意 Unicode 字母
Identifier    ::= Letter { Letter | Digit }
```

- 标识符区分大小写。
- `it` 是「特殊标识符」：在单参 Lambda 中隐式绑定（见 §7.4）。
- 命名约定（编译器检查，违反给 warning）：
  - 类型名：`UpperCamelCase`；类型参数：单个大写字母或 `UpperCamelCase`。
  - 变量/函数/属性：`lowerCamelCase`；常量：`UPPER_SNAKE_CASE`。
  - 命名约定也是表达式歧义消解的依据之一（§7.7）。

## 2.4 关键字

**基础关键字（30 个，不可作标识符）：**

```
fun    data   service  async   parallel  try   catch  finally
if     else   when     for     while     return
import package new      unsafe  native   stack
in     is     as       this    super
break  continue true    false   null
```

**软关键字（上下文中保留，可作标识符）：**

```
extends  implements  override  private  protected
```

**扩展关键字（由编译器插件注入，如 YuxPlugin 的 minecraft 扩展）：**

```
plugin   event   command   config   (见第 9 章与 03 文档)
```

> 注：`Int`/`String` 等不是关键字，是 `yux.core` 中预定义的类型名（§10）。

## 2.5 字面量

```
IntegerLiteral ::= DecimalLiteral | HexLiteral | BinaryLiteral
DecimalLiteral ::= NonZeroDigit { ['_'] Digit } | '0' [Suffix]
HexLiteral     ::= '0x' HexDigit { ['_'] HexDigit } [Suffix]
BinaryLiteral  ::= '0b' BinDigit { ['_'] BinDigit } [Suffix]
Suffix         ::= 'L' | 'F' | 'D'          // Long / Float / Double
FloatLiteral   ::= DecimalPart ['.' Fraction] [Exponent] [Suffix]
                 | '.' Fraction [Exponent] [Suffix]
DecimalPart    ::= Digit { ['_'] Digit }
Fraction       ::= Digit { ['_'] Digit }
Exponent       ::= ('e'|'E') ['+'|'-'] Digit { Digit }
BooleanLiteral ::= 'true' | 'false'
CharLiteral    ::= '\'' (Char | Escape) '\''
StringLiteral  ::= '"' { Char | Escape | Interpolation } '"'
RawStringLiteral ::= '"""' { Char } '"""'    // 三引号原始字符串，不解析插值/转义
Escape         ::= '\' ( 'n' | 't' | 'r' | 'b' | 'f' | '\\' | '\'' | '"' | '$' | '0' | 'u' HexDigit HexDigit HexDigit HexDigit )
Interpolation  ::= '$' Identifier | '${' Expression '}'
```

**语义：**

- S-2.5.1 整型默认 `Int`；`L` 后缀 → `Long`；超出 `Int` 范围的十进制字面量自动提升为 `Long`。
- S-2.5.2 `F`/`D` 后缀分别表示 `Float`/`Double`；带小数点或指数的浮点字面量默认为 `Double`。
- S-2.5.3 数字可用 `_` 分隔（`1_000_000`），无语义作用。
- S-2.5.4 插值 `$name`：`name` 取最长合法标识符；`${expr}` 允许任意表达式；转义 `\$` 表示字面 `$`。
- S-2.5.5 `RawStringLiteral`（`"""`）内不处理转义与插值，保留原始字符。

示例：

```yux
age = 18            // Int
count = 1_000_000L  // Long
pi = 3.14F          // Float
msg = "Hello $name, next=${age + 1}"
raw = """\n 原样输出 $不解析"""
ch = 'a'
```

## 2.6 运算符与分隔符

```
.   ,   :   ;   (   )   [   ]   {   }
+   -   *   /   %   =   ==  !=  <   >   <=  >=
&&  ||  !   +=  -=  *=  /=  %=  ..  ->
```

- `->` 用于 Lambda 与 `when` 分支。
- `..` 用于区间。
- 方括号 `[ ]` 预留（下划线/索引语法），v0.1 不启用。

---

# 3. 语法总览

```
Program ::= { PackageDecl | ImportDecl | TopLevelDecl }

PackageDecl ::= 'package' QualifiedName StmtEnd
ImportDecl  ::= 'import' QualifiedName ['.*'] StmtEnd

TopLevelDecl ::= ClassDecl | DataClassDecl | ServiceDecl | FunctionDecl
               | PropertyDecl | ExtensionHook

StmtEnd ::= [';'] NEWLINE
```

- S-3.1 每个 `.yux` 文件可包含多个顶级声明；顶级对象（类/`data`/`service`/函数/属性）默认就是 JVM 类或静态成员（§5）。
- S-3.2 缺省 `package` 时归入默认包；`import` 引入 Java/Kotlin 类型或 Yux 类型。

---

# 4. 类型系统

## 4.1 类型文法

```
Type ::= SimpleType [TypeArg+] ['?']

SimpleType ::= Identifier                       // 简单名，如 String
             | Identifier { '.' Identifier }    // 限定名，如 java.util.Date

TypeArg ::= Type                                // 位置参数，见 S-4.3

FunctionType ::= '(' [Type { ',' Type }] ')' '->' Type
```

**基础类型（`yux.core` 预定义）：**

```
Int  Long  Float  Double  Boolean  Char  String  Byte
Any  Unit  Nothing  Range
```

**语义：**

- S-4.1.1 `Any` 对应 JVM `java.lang.Object`；`Unit` 对应 `void`（函数无返回值的类型）；`Nothing` 是空底类型（永不返回，如 `throw` 的返回类型）。
- S-4.1.2 `Byte/Int/Long` 映射 JVM 原生类型；与 Java 自动拆装箱规则一致。
- S-4.1.3 类型名区分大小写；类型名约定大写（§2.3）。

## 4.2 可空类型

- S-4.2.1 `T?` 表示可空类型；`T` 表示非空类型。
- S-4.2.2 对可空接收者的读取自动插入空值守卫（见 §8.1「编译提醒 + 运行保护」），不要求显式 `?.`。

```yux
name:String? = maybeNull()

print name.length      // 编译为 if(name != null) name.length，null 时短路
```

## 4.3 泛型（无尖括号）

```
List String        // = List<String>
Map String Int     // = Map<String, Int>
Result Int         // 用户定义：data Result T { ... }
```

- S-4.3.1 泛型实参用「空格分隔的位置参数」，顺序即类型参数的声明顺序。
- S-4.3.2 实参数目必须与类型参数目一致（编译错误）。
- S-4.3.3 类型参数声明（§5.1）使用相同语法：`data Result T { ... }`。
- S-4.3.4 泛型类型在 JVM 上实现为擦除（erasure），与 Java 一致；保留 `Signature` 供反射使用。

## 4.4 函数类型与 Lambda 类型

```yux
// 函数类型
f:(Int) -> Int

// 使用
increment = (x -> x + 1) as (Int) -> Int
```

- S-4.4.1 函数类型映射 JVM 接口（`kotlin.jvm.functions.Function1` 等价物，或自定义接口）。
- S-4.4.2 函数类型是值类型，可赋值、可作参数与返回值。

## 4.5 类型推断（ADR-08：局部双向推断）

- S-4.5.1 变量声明省略类型时，类型由初始化表达式推断。
- S-4.5.2 推断为**局部**双向：从左（声明上下文）与右（初始化表达式）双向收紧，不做全局 Hindley-Milner。
- S-4.5.3 函数参数**必须**显式类型（编译错误）；函数返回值可省略，由 `return` 语句推断，若函数体为空则为 `Unit`。
- S-4.5.4 推断失败时给出带表达式的定位诊断，不猜测类型。

```yux
name = "Steve"      // name:String
age = 18            // age:Int
scores:List Int = []   // 显式声明 + 空集合
```

---

# 5. 声明与对象

## 5.1 类声明

```
ClassDecl ::= ['private'|'protected'] Identifier [TypeParamDecl] ['extends' Type] ['implements' Type {',' Type}] ClassBody

TypeParamDecl ::= Identifier { Identifier }     // 单个大写字母/UpperCamelCase

ClassBody ::= '{' { ClassMember } '}'
ClassMember ::= PropertyDecl | FunctionDecl | BlockDecl
```

- S-5.1.1 **不写 `class` 关键字**：`Identifier { ... }` 即声明一个 JVM 类。
- S-5.1.2 生成 `public class Player`；`private`/`protected` 为软关键字修饰类。
- S-5.1.3 属性按声明顺序生成全参构造器（`Player(name, health)`），构造器参数顺序即属性声明顺序。
- S-5.1.4 空类体允许。

```yux
Player {
    name:String
    health:Int
}

player = Player("Steve", 20)
```

## 5.2 属性（Property）

```
PropertyDecl ::= Identifier ':' Type [ '=' Expression ] [ PropertyAccessors ]
               | Identifier '=' Expression                      // 推断类型的属性

PropertyAccessors ::= '{' AccessorDecl { AccessorDecl } '}'
AccessorDecl ::= 'get' Block | 'set' Block
```

**语义：**

- S-5.2.1 属性默认自动生成 JVM getter/setter：
  - `name:String` → `getName()` / `setName(String)`（Boolean 属性：`isXxx()`）。
- S-5.2.2 省略 setter 的自定义属性：只写 `get { ... }` 时属性只读。
- S-5.2.3 访问器块内绑定：`value` 指代 backing field；setter 内 `set` 指代传入的新值。
- S-5.2.4 有自定义访问器时不生成默认访问器。
- S-5.2.5 带初始化 `= expr` 的属性：构造时先执行初始化表达式，再执行构造器体。

```yux
Player {
    health:Int {
        get { return value }
        set { value = clamp(set, 0, 20) }
    }
}
```

## 5.3 data 类

```
DataClassDecl ::= 'data' Identifier [TypeParamDecl] ['extends' Type] ['implements' Type {',' Type}] DataBody
DataBody ::= '{' { DataMember } '}'
DataMember ::= PropertyDecl
```

**语义（S-5.3.1 自动生成）：**

- 全参构造器（属性声明顺序）；
- `equals`/`hashCode`（基于全部属性）；
- `toString`（格式 `User(id=1, name=Steve)`）；
- 序列化器（`yux.serializer`，见 §8.3）；
- 无自定义访问器时属性为 `val` 语义（只读）；可覆盖为可变。

```yux
data User {
    id:Int
    name:String
}

u = User(1, "Steve")
print u.toString       // User(id=1, name=Steve)
json = serialize(u)    // {"id":1,"name":"Steve"}
```

## 5.4 service 声明（自动依赖注入）

```
ServiceDecl ::= 'service' Identifier [TypeParamDecl] ServiceBody
ServiceBody ::= '{' { ServiceMember } '}'
ServiceMember ::= PropertyDecl | FunctionDecl
```

**语义（S-5.4.1）：**

- `service` 声明一个由 `yux.di` 容器托管、构造时自动解析依赖的单例服务。
- 属性中「类型为已注册 service」的字段自动注入；其余属性需提供初始值。
- 服务依赖注入图在程序启动时构建，检测循环依赖并报错。
- JVM 上生成普通类 + `@YuxService` 注解（供 `yux.di` 扫描）。

```yux
service Database {
    connect() {
        // ...
    }
}

service UserService {
    database:Database
    users() = database.query("SELECT * FROM users")
}

svc = inject(UserService)      // 自动构造 Database 并注入
```

## 5.5 函数声明

```
FunctionDecl ::= ['async'] ['override'] ['private'|'protected'] 'fun' Identifier [TypeParamDecl] Parameters [':' Type] FunctionBody

Parameters ::= '(' [ Parameter { ',' Parameter } ] ')'
Parameter ::= Identifier ':' Type [ '=' Expression ]

FunctionBody ::= Block | '=' Expression StmtEnd
```

**语义：**

- S-5.5.1 函数参数必须显式类型（ADR-08）。
- S-5.5.2 返回类型省略时：函数体为块 → 由 `return` 推断，缺省为 `Unit`；单表达式 `= expr` → 推断为 `expr` 的类型。
- S-5.5.3 顶级函数编译为所在文件的静态方法（文件编译为 JVM 类，如 `Main.kt` 等价物）。
- S-5.5.4 `async fun`：函数体在协程调度器上执行（§8.4）。
- S-5.5.5 参数默认值：省略实参时使用 `= Expression`。

```yux
fun add(a:Int, b:Int) = a + b        // 推断 :Int
fun greet(name:String, title:String = "Mr.") {
    print "Hello $title $name"
}
async fun loadPlayer(id:Int):Player { /* 协程 */ }
```

## 5.6 顶级属性

- S-5.6.1 顶级属性编译为所在文件的静态字段 + 访问器。
- S-5.6.2 用 `const` 风格命名（§2.3）且初始化表达式为编译期常量时，生成 `static final`。

```yux
maxPlayers = 100
serverName = "YuxServer"
```

## 5.7 导入与包

```yux
package com.example

import java.util.UUID
import org.bukkit.*
import yux.json.*
```

- S-5.7.1 `import x.*` 导入包内全部类型。
- S-5.7.2 与 Java 互操作：类型名可直接使用，编译器解析 Java/Kotlin 类符号。

---

# 6. 语句

```
Statement ::= VarDecl | Assign | ExprStmt | IfStmt | WhenStmt
            | ForStmt | WhileStmt | ReturnStmt | BreakStmt | ContinueStmt
            | TryStmt | AsyncStmt | ParallelStmt | UnsafeStmt | Block

Block ::= '{' { Statement } '}'
```

- S-6.1 语句以换行或 `;` 终结；块内语句同理。

## 6.1 变量声明与赋值

```
VarDecl ::= Identifier ':' Type [ '=' Expression ] | Identifier '=' Expression
Assign  ::= Assignable AssignOp Expression
AssignOp ::= '=' | '+=' | '-=' | '*=' | '/=' | '%=' | '..='
Assignable ::= Identifier | Postfix
```

- S-6.1.1 语句位置的 `Identifier = expr`：若该标识符此前未声明 → 声明；已声明 → 赋值。（与 Python 规则一致，由符号表判断。）
- S-6.1.2 声明时不赋初值的局部变量，在首次使用前必须被赋值（确定性赋值分析）。

```yux
name = "Steve"      // 声明
name = "Alex"       // 赋值
age:Int = 18
total += 1
```

## 6.2 if / else

```
IfStmt ::= 'if' Expression ( Block | Statement )
        | 'if' Expression ( Block | Statement ) 'else' ( 'if' Expression ... | Block | Statement )
```

- S-6.2.1 条件必须是 `Boolean`（不自动转换数字）。
- S-6.2.2 `if/else` 是语句，不产生值（与 Java 一致）；表达式位置用 `when` 或三元省略。

```yux
if health <= 0 {
    player.send "你死了"
} else if health < 10 {
    player.send "血量低"
} else {
    player.send "状态良好"
}
```

## 6.3 when

```
WhenStmt ::= 'when' Expression '{' WhenBranch+ ['else' '->' (Block | Statement)] '}'
WhenBranch ::= WhenCondition '->' (Block | Statement)
WhenCondition ::= Expression | 'is' Type | 'else'
```

- S-6.3.1 `is T` 分支在匹配时执行智能转型（smart cast），分支体内可按 `T` 使用该表达式。
- S-6.3.2 分支条件自上而下求值；无 `else` 且无匹配时，`when` 无副作用。

```yux
when item {
    is Sword -> print "武器 ${item.damage}"
    1 -> print "one"
    "hi" -> print "hello"
    else -> print "其他"
}
```

## 6.4 for / while

```
ForStmt ::= 'for' Identifier 'in' Expression Block
WhileStmt ::= 'while' Expression Block
RangeExpr ::= Expression '..' Expression        // 仅用于 for 的 in 子句或表达式上下文
```

- S-6.4.1 `for x in list`：迭代任何可迭代对象（JVM `Iterable`/数组/`Range`）。
- S-6.4.2 `for i in 0..10` 为闭区间 `[0,10]`；`0..10` 生成 `Range`。
- S-6.4.3 `break` 跳出当前循环；`continue` 进入下一轮（软作用域内）。

```yux
for i in 0..10 {
    print i
}

for player in onlinePlayers() {
    player.send "在线"
}
```

## 6.5 return / break / continue

```
ReturnStmt ::= 'return' [ Expression ] StmtEnd
BreakStmt  ::= 'break' StmtEnd
ContinueStmt ::= 'continue' StmtEnd
```

- S-6.5.1 `return` 无表达式用于 `Unit` 函数。
- S-6.5.2 `return` 在 Lambda 中返回外层函数（与 Kotlin 的非局部返回一致）——见 §7.4 例外规则。

## 6.6 异常

```
TryStmt ::= 'try' ( Block | Expression StmtEnd ) { CatchClause } [ 'finally' Block ]
CatchClause ::= 'catch' Identifier [ ':' Type ] Block
```

- S-6.6.1 复用 JVM 异常模型；`catch` 未指定类型时捕获 `Throwable`。
- S-6.6.2 简写 `try Expression`：自动展开为 `try { Expression }`（如 `try loadConfig()`）。
- S-6.6.3 `throw` 是表达式（产生 `Nothing`），见 §7.1。

```yux
try loadConfig()
catch e {
    print "配置加载失败: $e"
}

try {
    risky()
} catch e:IOException {
    print "IO 错误"
} finally {
    close()
}
```

## 6.7 async / parallel

```
AsyncStmt    ::= 'async' Block
ParallelStmt ::= 'parallel' Block
UnsafeStmt   ::= 'unsafe' Block
```

- S-6.7.1 `async { }` 在协程调度器上启动一段异步代码，不阻塞当前线程（§8.4）。
- S-6.7.2 `parallel { }` 将块内无数据依赖的调用提交到 ForkJoinPool 并行执行，块结束处等待全部完成（§8.4）。
- S-6.7.3 `unsafe { }` 允许在块内使用 `unsafe` 能力（见 §8.6）。

```yux
async {
    data = await fetchRemote()
    print data
}

parallel {
    download()
    calculate()
}
```

---

# 7. 表达式

## 7.1 表达式文法

```
Expression ::= AssignmentExpr
AssignmentExpr ::= ConditionalOr [ AssignOp Expression ]

ConditionalOr ::= ConditionalAnd { '||' ConditionalAnd }
ConditionalAnd ::= Equality { '&&' Equality }
Equality ::= TypeTest { ( '==' | '!=' ) TypeTest }
TypeTest ::= Comparison [ ( 'is' | 'as' ) Type ]
Comparison ::= Additive { ( '<' | '>' | '<=' | '>=' ) Additive }
Additive ::= Multiplicative { ( '+' | '-' ) Multiplicative }
Multiplicative ::= Unary { ( '*' | '/' | '%' ) Unary }
Unary ::= ( '!' | '-' ) Unary | Postfix

Postfix ::= Primary { '.' Identifier [CallSuffix] | CallSuffix | '?' }

Primary ::= Literal | Identifier [CallSuffix] | TypeCall | Lambda | BlockLambda | '(' Expression ')' | 'throw' Expression

CallSuffix ::= '(' [ Argument { ',' Argument } ] ')' | Argument
Argument ::= Expression | BlockLambda
TypeCall ::= TypeRef '(' [ Argument { ',' Argument } ] ')'     // 构造调用
TypeRef  ::= SimpleType [ TypeArg+ ]

Lambda ::= ParameterName [ ',' ParameterName ]* '->' Expression
BlockLambda ::= Block    // 仅作参数/赋值时是 Lambda（隐式 it）
```

**优先级表（自高而低）：**

| 级别 | 运算符 |
|---|---|
| 1（最高） | 字面量 / 标识符 / 括号 / `(args)` 调用 / 无括号单参调用 / 构造 |
| 2 | 后缀 `.prop`、`.` call、`?` |
| 3 | 一元 `!` `-` |
| 4 | `*` `/` `%` |
| 5 | `+` `-` |
| 6 | `<` `>` `<=` `>=` `is` `as` |
| 7 | `==` `!=` |
| 8 | `&&` |
| 9 | `\|\|` |
| 10（最低） | 赋值 `=` `+=` ...（右结合） |

## 7.2 调用语法

- S-7.2.1 常规调用：`callee(a, b)`。
- S-7.2.2 **无括号单参调用**：`callee arg` 等价于 `callee(arg)`，其中 `arg` 为紧随其后的单个 `Primary` 表达式（字面量/标识符/括号表达式/Lambda 块）。
  - 绑定优先级高于二元运算符：`f a + b` 解析为 `f(a) + b`；传复杂实参请用 `f(a + b)`。
  - 无参调用必须带括号：`list.size()`；`list.size` 是属性访问。
- S-7.2.3 链式调用：`player.inventory.add item` 解析为 `player.getInventory().addItem(item)`（JavaBean 映射见 §8.5）。

```yux
player.send "Welcome"                // player.send("Welcome")
player.inventory.add item            // player.getInventory().addItem(item)
print player.name + " 上线了"          // print(player.getName() + " 上线了")
```

## 7.3 构造调用

- S-7.3.1 `Type(args)` 或 `Type TypeArg (args)` 为构造调用。
- S-7.3.2 解析歧义消解（`F(...)` 是函数调用还是构造调用）依赖符号表：标识符解析为类型 → 构造；解析为函数 → 函数调用（§7.7）。

```yux
player = Player("Steve", 20)
result = Result Int(true, 5)
list = List String()
```

## 7.4 Lambda

- S-7.4.1 显式 Lambda：`x -> x + 1`；多参：`(a, b) -> a + b`。
- S-7.4.2 **隐式 `it`**：单参 Lambda 可省略参数名，体内直接使用 `it`：
  - 参数块形式：`players.map { it.name }`（`BlockLambda`，仅可作调用实参或赋值右值）。
  - 箭头形式必须写参数名。
- S-7.4.3 Lambda 内的 `return` 是**非局部返回**（返回外层函数）；仅返回 Lambda 用 `it` 表达式值。
- S-7.4.4 Lambda 的 `this` 不变；捕获外层局部变量（闭包）。

```yux
names = players.map { it.name }
sorted = players.sort (a, b -> a.health > b.health)
count = numbers.reduce (0, (acc, n -> acc + n))   // 显式多参 Lambda
```

## 7.5 运算符语义

- S-7.5.1 `+` 对 `String` 重载为拼接；其余为数值/基本语义，映射 JVM 指令。
- S-7.5.2 `==`/`!=`：对基本类型为值比较；对引用类型为结构相等（生成 `equals`），`===` 保留 JVM 引用比较（软记号，暂不启用）。
- S-7.5.3 `is T`：类型检查；`as T`：强制转换，失败抛 `ClassCastException`。
- S-7.5.4 无隐式数字转换（Int → Long 需显式 `.toLong()`，与 Kotlin 一致）；`a` 字面量除外（字面量按目标类型收窄）。

## 7.6 字符串与插值

```yux
name = "Steve"
print "欢迎 $name"
print "下次升到 ${level + 1} 级"
print "\$name"           // 输出 $name
```

- S-7.6.1 `$name`/`${expr}` 编译为 `StringBuilder.append(...)` 等价字节码。

## 7.7 解析歧义消解规则

解析器持有「已声明符号表」（类型名/函数名/变量名）。歧义情形与消解顺序：

1. `Identifier Identifier`（连续两个标识符）：
   - 第一个为类型、第二个为类型 → 泛型构造（`Result Int(...)`）；
   - 第一个为函数 → 无括号调用（`send "Welcome"`）；
   - 第一个为变量且第二个为 Primary → 无括号调用（`player.send "Welcome"`）。
2. `Identifier ( ... )`：类型 → 构造；函数 → 调用。
3. 点号链中最后一个 `.Identifier` 后跟 `(` 时是调用，否则是属性访问；`java.util.List` 在**类型位置**为限定类型名。
4. 消解失败时按命名约定（大写→类型）回退并给出 warning，兜底按变量/函数处理。

> 详细实现见 `02-Yux-编译器架构.md` §6「消歧与符号表」。

---

# 8. 特性语义

## 8.1 空安全：编译提醒 + 运行保护（ADR-10）

**规则（S-8.1.x）：**

- `T?` 声明可空；对可空接收者 `a.b`，编译器插入空守卫：`a` 为 null 时整条读表达式短路为类型默认值/空集合，不抛 NPE。
- 守卫同时产生编译提醒（warning），提示开发者显式处理可空性。
- 写路径（`a.b = x`、调用副作用方法）不自动守卫；需显式 `if a != null`。
- 显式断言非空：`a as T`（失败抛异常）。
- 与 Java 互操作：Java 类型一律视为可空（`T?`），由守卫保护。

```yux
name:String?
print name.length        // 自动 if(name != null)，warning 提示

if name != null {        // 显式：智能转型为非空
    print name.length
}
```

## 8.2 注解系统

```
Annotation ::= '@' QualifiedName [ '(' [ AnnotationArg { ',' AnnotationArg } ] ')' ]
AnnotationArg ::= Identifier '=' Expression
```

- S-8.2.1 注解编译为 JVM Annotation，可作用于类/属性/函数/参数。
- S-8.2.2 兼容 Java 注解（Spring、Jackson、JPA、Minecraft API）与 Kotlin 注解。

```yux
@Serializable
data PlayerData {
    name:String
    level:Int
}

@EventHandler(priority = HIGH)
event PlayerJoin(player) { /* 见 03 SDK */ }
```

## 8.3 自动序列化（yux.serializer）

- S-8.3.1 `data` 类自动生成序列化器，支持 JSON / YAML / NBT（Minecraft）。
- S-8.3.2 顶层函数：`serialize(x):String`、`deserialize<T>(text, type):T`。
- S-8.3.3 NBT 序列化由 `yux.minecraft` 扩展提供（见 `03`）。

```yux
data PlayerData {
    name:String
    level:Int
}

p = PlayerData("Steve", 10)
json = serialize(p)                // {"name":"Steve","level":10}
restored = deserialize(json, PlayerData)
```

## 8.4 自动并发（yux.async）

- S-8.4.1 `async fun` / `async { }`：编译为 JVM 协程（基于 `kotlinx.coroutines` 运行时，或自研轻量调度器）。
- S-8.4.2 `await(x)` 挂起等待协程完成。
- S-8.4.3 `parallel { }`：块内无数据依赖的调用提交到 ForkJoinPool；块尾隐式 `join`。
- S-8.4.4 调度器与 Java 虚拟线程（`java.util.concurrent`）可互操作。

```yux
async fun loadPlayer(id:Int):Player {
    data = await fetch("api/players/$id")
    return deserialize(data, Player)
}

parallel {
    download()
    calculate()
}
```

## 8.5 Java / Kotlin 互操作

- S-8.5.1 直接调用 Java：`Bukkit.getPlayer(uuid)`。
- S-8.5.2 JavaBean 映射：Java `getX()`/`setX()` 可用属性语法 `x` 访问。
- S-8.5.3 Java 静态成员：`ClassName.member`。
- S-8.5.4 Kotlin 类型可直接使用；Kotlin 侧引用 Yux 类型按普通 JVM 类型处理。
- S-8.5.5 Java 注解、接口、异常全部原生可用。

```yux
import org.bukkit.Bukkit
import org.bukkit.entity.Player

player = Bukkit.getPlayer(uuid)
player.name              // = player.getName()
player.health = 20       // = player.setHealth(20)
player.inventory.add item // 链式属性 + 调用
```

## 8.6 内存模型

- S-8.6.1 默认 GC 管理：`player = Player()`。
- S-8.6.2 高级：`stack x = Player()` 表示栈分配意图（转义分析/未来 Native 后端；JVM 上 v0.1 映射为对象分配并开启逃逸分析提示）。
- S-8.6.3 `unsafe { }`：允许直接内存操作与原始指针（面向高手/底层库），须显式开启。
- S-8.6.4 `native fun`：声明由 Native 后端/外部库提供的函数（JVM 上映射 `@JvmNative` 或 `java.lang.foreign`）。

```yux
player = Player()          // GC
stack hot = HotObj()       // 栈分配意图
native fun syscall(n:Int):Long
```

## 8.7 继承与接口

- S-8.7.1 单继承 `extends`；多接口 `implements`。
- S-8.7.2 `override` 覆盖父类/接口成员；编译器校验 override 合法性。
- S-8.7.3 类默认 `final`；父类需显式开放（软关键字 `open` 为未来扩展，v0.1 覆盖 Java `public class` 默认行为）。

```yux
PlayerListener extends Listener {
    override onTick() { }
}
```

---

# 9. 编译器插件扩展点

- S-9.1 Yux 语言本体只含第 2~8 章的语法。领域语法（`plugin`/`event`/`command`/`config` 等）由**编译器插件**注入。
- S-9.2 编译器插件通过 `yux-plugin-api` 扩展：
  1. **扩展关键字**：向解析器注册新关键字与产生式（`event`、`command`...）；
  2. **AST 变换**：将领域语法变换为普通 Yux 声明（如 `event` → 类 + `@EventHandler` 函数）；
  3. **语义检查**：领域约束校验；
  4. **代码生成钩子**：额外生成 JVM 类（如 `plugin.yml`、`plugin.yml` 对应的注册代码）。
- S-9.3 扩展点接口签名定义于 `02-Yux-编译器架构.md` §9；Minecraft 扩展的完整设计见 `03-YuxPlugin-SDK-设计.md`。

```yux
// 下列语法不是语言本体，而是 YuxPlugin 扩展注入后可用：
plugin MyServer { }

event PlayerJoin(player) { player.send "欢迎" }

command "/home" { teleport player home }

config Server {
    motd:String
    maxPlayers:Int
}
```

---

# 10. 标准库概览（yux.*）

> 完整 API 表待 `03` 与标准库文档细化；此处给出 v0.1 承诺的核心签名。

## 10.1 yux.core

```yux
fun print(x:Any)
fun println(x:Any)
fun clamp(v:Int, min:Int, max:Int):Int        // 重载 Long/Float/Double
fun assert(cond:Boolean)
fun range(from:Int, to:Int):Range
type Int, Long, Float, Double, Boolean, Char, String, Byte, Any, Unit, Nothing, Range
// String
String.length:Int
String.substring(start:Int, end:Int):String
String.contains(s:String):Boolean
String.split(sep:String):List String
String.trim():String
String.uppercase():String / lowercase()
String.format(vararg args:Any):String
// 数值
Int.toLong():Long / toDouble() / toString()
```

## 10.2 yux.collection

```yux
type List T, MutableList T, Map K V, MutableMap K V, Set T
List.size:Int / isEmpty():Boolean / contains(x:T):Boolean / get(i:Int):T
map(f:(T)->U):List U / filter(f:(T)->Boolean):List T
forEach(f:(T)->Unit) / sort(f:(T,T)->Boolean)
sum():T / max():T / first():T / reduce(f:(T,T)->T)
Map.get(k:K):V? / containsKey(k:K):Boolean / keys / values / put(k,v) / remove(k)
```

## 10.3 yux.io

```yux
fun readText(path:String):String
fun writeText(path:String, text:String)
fun readLines(path:String):List String
fun exists(path:String):Boolean
```

## 10.4 yux.net

```yux
fun httpGet(url:String):String
fun httpPost(url:String, body:String):String
async fun fetch(url:String):String
```

## 10.5 yux.json

```yux
fun serialize(x:Any):String
fun deserialize<T>(text:String, type:T):T
type JsonValue    // get(key), asInt, asString, asList, asObject ...
```

## 10.6 yux.async

```yux
fun async(block:()->Unit):Task
fun await(task:Task):Any?
fun parallel(block:()->Unit)
fun sleep(ms:Long)
fun launch(block:()->Unit):Task
type Task          // await() / cancel() / isDone
```

## 10.7 yux.di

```yux
fun inject<T>():T
type Provider<T>   // get():T
@YuxService
```

## 10.8 yux.test

```yux
@Test
fun assertEquals(expected:Any, actual:Any)
fun assertTrue(cond:Boolean)
fun assertNull(x:Any?)
```

## 10.9 yux.minecraft（插件实现库，见 03）

```yux
plugin / event / command / config 关键字
NBT 序列化、Paper API 包装、权限/命令注册
```

---

# 附录 A：规范版本与变更

| 版本 | 说明 |
|---|---|
| v0.1 | 设计稿（Design.md） |
| v0.2 | 本文：完整词法/语法/语义规范 |
| 待定 | 标准库正式 API 表；KMP/Native 语义补充 |

# 附录 B：与 v0.1 草稿的差异记录

- `get:` / `set:` 访问器形式正式化为 `get { }` / `set { }`（保证与「花括号+换行」风格一致）。
- 新增软关键字 `extends`/`implements`/`override`/`private`/`protected`（基础关键字仍为 30 个）。
- 明确 `$` 插值转义为 `\$`；新增 `"""` 原始字符串。
- 明确无括号单参调用的绑定规则（优先于二元运算符）。
- 新增解析歧义消解规则（§7.7）。
