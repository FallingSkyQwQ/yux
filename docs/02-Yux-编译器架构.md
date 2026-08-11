# Yux 编译器架构

> 前置：`01-Yux-语法规范.md`。本设计对应 ADR-02/03/04/12/14。
> 实现语言：Kotlin；解析器：手写递归下降；字节码：ASM 直接生成。

## 目录

1. 总体管线
2. 模块划分（Kotlin 工程）
3. 阶段流水线
4. 词法分析器（Lexer）
5. 语法分析器（Parser）
6. 消歧与符号表
7. 语义分析（Semantic Analyzer）
8. IR 设计
9. JVM 后端（ASM）
10. 编译器插件 API
11. 构建管线（build.yml → Gradle）
12. 错误诊断与增量编译
13. 未来后端预留（Native / WASM）
14. 源码目录结构

---

# 1. 总体管线

```
.yux 源文件
    │
    ▼
┌─────────────┐   tokens   ┌─────────────┐   CST/AST  ┌─────────────┐
│   Lexer     │───────────►│   Parser    │───────────►│  SymbolRes  │
└─────────────┘            └─────────────┘            └─────────────┘
                                                              │ 已解析AST
                                                              ▼
┌─────────────┐   IR       ┌─────────────┐   IR      ┌─────────────┐
│ JVM Backend │◄───────────│   Optimizer │◄──────────│  TypeCheck  │
│  (ASM)      │  (可选)     │  (可选)     │◄──────────│  +Inference │
└─────────────┘            └─────────────┘            └─────────────┘
    │  .class
    ▼
依赖类文件 + 生成的 build.gradle.kts → Gradle 打包
```

第一阶段目标链：`Yux → Yux IR → JVM Bytecode`（ADR-04）。
未来目标链：`Yux → Yux IR → LLVM → Native`（§13）。

---

# 2. 模块划分（Kotlin 工程）

编译器以 Gradle 多模块 Kotlin 工程组织：

```
yux/
├── yux-compiler/            # 核心编译器（无外部命令行依赖）
│   ├── yux-compiler-core/       # Lexer/Parser/AST/Sema/IR
│   ├── yux-compiler-backend-jvm/ # JVM 后端（ASM）
│   ├── yux-compiler-cli/        # 命令行入口 yuxc
│   └── yux-compiler-plugin-api/ # 编译器插件 SPI（§10）
├── yux-plugin-minecraft/    # Minecraft 语言扩展 + 运行时（见 03）
├── yux-stdlib/              # 标准库 yux.core 等（Kotlin 实现）
└── build-tool/              # build.yml → Gradle 生成器
```

模块职责边界：

| 模块 | 依赖 | 职责 |
|---|---|---|
| `yux-compiler-core` | ASM（仅后端用，或下沉） | 词法→语法→语义→IR |
| `yux-compiler-backend-jvm` | core + ASM | IR→`.class` |
| `yux-compiler-plugin-api` | core（仅 AST/IR 类型） | 扩展 SPI |
| `yux-compiler-cli` | core+backend+plugin | `yuxc` 命令 |
| `build-tool` | — | 解析 build.yml，生成 Gradle 脚本并调用 |

> 插件 API 与 core 解耦：插件编译期依赖 `plugin-api` 与 `core` 的公开类型；运行时插件（如 minecraft 扩展）以 Jar 形式随编译命令加载。

---

# 3. 阶段流水线

每个阶段是纯函数：`State → Result<State, Diagnostics>`，便于单测与增量缓存。

```
Pipeline:
  SourceSetLoader
    → Lexer           （文件 → Token 流）
    → Parser          （Token 流 → CST）
    → PluginPrePass   （扩展关键字/产生式；插件注册，§10）
    → SymbolResolver  （声明收集 + 符号表 + 消歧）
    → TypeChecker     （类型检查 + 局部推断 + 智能转型）
    → Lowering        （语法糖/领域语法下沉为普通 AST）
    → IRGen           （AST → IR）
    → Optimizer       （可选，见 §8.4）
    → Backend         （IR → .class / 未来 Native）
```

- S-3.1 每阶段产出不可变数据结构，跨阶段以 `SessionContext` 传递（源码路径、诊断收集器、插件上下文）。
- S-3.2 诊断（Diagnostic）分级：`ERROR / WARNING / REMIND`（空安全提醒用 `REMIND`）。

---

# 4. 词法分析器（Lexer）

## 4.1 设计

- 手写逐字符扫描，输出 `Token(position, kind, text, leadingTrivia)`。
- `Trivia`（注释、空白、换行）保留在 Token 上，供错误恢复、格式化与文档注释提取。
- 换行产出 `NEWLINE` 记号（§01-2.1）。

## 4.2 Token 种类

```
IDENTIFIER, INT_LITERAL, FLOAT_LITERAL, CHAR_LITERAL,
STRING_LITERAL, RAW_STRING_LITERAL, INTERPOLATION_START('$'),
KEYWORD, SOFT_KEYWORD, SYMBOL, NEWLINE, EOF
```

## 4.3 插值处理

- `$name`：词法器产出 `INTERPOLATION_START` + `IDENTIFIER`，语法层拼接为 `StringTemplate`。
- `${expr}`：进入「括号平衡表达式模式」，表达式解析结束后恢复字符串扫描（嵌套字符串递归进入）。
- `\$` 与 `"""` 原始字符串按 §01-2.5 处理。

## 4.4 错误恢复

- 非法字符：跳过并报告 `ERROR`，不终止编译（单文件多个错误全部上报）。

---

# 5. 语法分析器（Parser）

## 5.1 技术选型（ADR-03）

手写递归下降（Pratt 表达式解析）：
- 每个非终结符对应一个 `parseXxx()` 方法。
- 表达式用 Pratt：前缀（`Primary`）+ 中缀/后缀绑定力表，直接实现 §01-7.1 优先级。
- 优点：无代码生成、报错位置可控、插件可挂钩子（§10）。

## 5.2 CST 与 AST

- 解析器先产出 **CST**（含全部括号/换行/注释的完整语法树），经 `CstToAst` 规约出 **AST**（去除冗余记号，节点带源码范围 `SourceSpan`）。
- AST 节点基类：`YxNode(span)`；类型节点、声明节点、表达式节点、语句节点。

## 5.3 语句终结规则

- 语句以 `NEWLINE` 或 `;` 终结（§01-6）。
- Parser 在表达式解析后检查「下一记号是否为 NEWLINE/; 或结构终结符（} 等）」来决定语句是否结束；允许块内最后一条语句省略换行。

## 5.4 插值与无括号调用的解析

- 无括号单参调用：解析到 `Primary` 后，若下一记号不是中缀运算符、`)`、`]`、`}`、`NEWLINE`、`;`、`,`，则把该记号当作实参 `Argument` 继续解析一个 `Primary`（§01-7.2.2 绑定规则）。

---

# 6. 消歧与符号表（ADR 实现依据 §01-7.7）

## 6.1 符号表（SymbolTable）

层级结构：`GlobalScope → FileScope → DeclScope(类/函数) → BlockScope`。

```kotlin
interface Symbol {
    val name: String
    val span: SourceSpan
    val kind: SymbolKind   // TYPE, FUNCTION, VARIABLE, PROPERTY, SERVICE
}
```

## 6.2 两遍式解析（Two-Pass）

1. **声明收集遍**：遍历 CST 收集本文件所有类型/函数/属性名（含 Java/Kotlin import），建立符号表。
2. **主体解析遍**：用符号表消歧表达式与调用（§01-7.7 规则），并做作用域检查。

- 跨文件/库符号：通过 `ClassPathSymbolProvider` 懒加载 JVM 类元信息（解析 `java.util.List`、`org.bukkit.Bukkit` 等），缓存到 `SymbolCache`。

## 6.3 消歧算法（对应 §01-7.7）

```
resolveIdentifierChain(tokens):
  1. 首个标识符查符号表：
     - 类型 → 后续类型名视为类型实参；后随 '(' → 构造调用
     - 函数 → 函数调用（含无括号单参）
     - 变量/属性 → 无括号调用或属性访问
  2. 点号链：逐段解析成员；最后一段后随 '(' 为调用，否则为属性访问
  3. 均失败 → 按命名约定（大写→类型）回退 + WARNING
```

---

# 7. 语义分析（Semantic Analyzer）

## 7.1 类型检查

- 每个表达式推导 `YxType`（§8.1），节点属性 `expr.type`。
- 规则实现自 §01 的 `S-xx`：
  - 条件必须是 `Boolean`（S-6.2.1）
  - 参数类型必须匹配（含协变/逆变检查）
  - 确定性赋值分析（S-6.1.2）
  - `data` 类自动生成方法校验（S-5.3.1）
  - 访问器合法性（S-5.2.x）
  - 泛型实参数目一致（S-4.3.2）

## 7.2 局部双向推断（ADR-08）

实现为约束收集 + 传播：

```
1. 变量声明无类型 → 建立推断变量 ?T
2. 从初始化表达式向上传播（右→左）
3. 从使用上下文向下收紧（左→右，如参数、返回类型）
4. 约束求解：单次统一（unify），冲突即报错
5. 不做全局 HM；不猜测 Lambda 参数类型（必须显式）
```

- 推断结果写入 AST 并在 Lowering 阶段固化为显式类型标注。

## 7.3 智能转型（Smart Cast）

- `if x is T { ... }` 分支体内将 `x` 视为 `T`（§01-6.3.1）。
- 空安全守卫后的分支同样触发（§01-8.1）。
- 规则：若 `x` 为不可变（声明后未重新赋值）才允许转型；可变引用转型报 `REMIND`。

## 7.4 空安全守卫的插入点

在 Lowering 阶段处理（§01-8.1）：
- 可空接收者 `.b` → 生成 `if(a != null) a.b else default` 的 IR；
- 附带 `REMIND` 诊断；
- 写路径/副作用调用不守卫，保持语义明确。

---

# 8. IR 设计

## 8.1 IR 中的类型表示

```kotlin
sealed class IrType {
    object Void
    object Nothing
    class Basic(val jvm: ClassDesc)        // Int/Long/String/...
    class Nullable(val base: IrType)        // T?
    class Generic(val name: String, val args: List<IrType>)
    class Function(val params: List<IrType>, val ret: IrType)
    class Declared(val classSymbol: YxClassSymbol, val args: List<IrType>)
    class TypeParam(val name: String, val bound: IrType?)
}
```

## 8.2 IR 结构（SSA-less，类字节码风格）

v0.1 IR 为**结构化、带类型的抽象字节码**（轻量、直接映射 JVM），编译期中间形态不保留 SSA；
完整的 SSA 优化器（§8.4）以该 IR 为输入做**分析期 SSA**——构造 CFG/φ 并在优化后销毁回此形态，
后端始终消费无 φ 的线性 IR：

```kotlin
sealed class IrStmt {
    class LocalAssign(val local: IrLocal, val value: IrExpr)
    class Call(val callee: IrExpr, val args: List<IrExpr>, val ret: IrType)
    class New(val type: IrType, val args: List<IrExpr>)
    class FieldAccess(val receiver: IrExpr?, val field: YxField, val write: Boolean)
    class Branch(val cond: IrExpr, val then: IrLabel, val else: IrLabel)
    class Goto(val label: IrLabel)
    class Return(val value: IrExpr?)
    class Throw(val value: IrExpr)
    class Monitor(val expr: IrExpr, val enter: Boolean)   // synchronized
    class TryCatch(val tryStart: IrLabel, ..., val handler: IrLabel, val type: IrType?)
    class Nop
}

sealed class IrExpr {
    class Const(val value: Any?)
    class LocalRead(val local: IrLocal)
    class FieldRead(val receiver: IrExpr?, val field: YxField)
    class Arith(val op: Op, val l: IrExpr, val r: IrExpr)
    class Compare(val op: Op, val l: IrExpr, val r: IrExpr)
    class Convert(val expr: IrExpr, val to: IrType)
    class Invoke(val method: YxMethod, val receiver: IrExpr?, val args: List<IrExpr>)
    class StringTemplate(val parts: List<IrExpr>)
    class NullGuard(val expr: IrExpr)                 // 空安全守卫
    class Lambda(val target: YxMethod)                 // 编译为内部类/方法引用
}
```

## 8.3 IR 层级

- `IrModule`（文件集合）→ `IrClass` → `IrMethod`/`IrField`/`IrProperty` → `IrStmt`/`IrExpr`。
- 属性（Property）在 IR 中体现为 `IrProperty`，包含 `backingField` + `getter`/`setter` 的 `IrMethod`（§01-5.2）。

## 8.4 优化器（可选管线）

v0.1 提供两级优化，默认开启（`Compiler.generate`：CPS 降级 → SSA 优化 → 最小优化）：

- **完整 SSA 优化**（`yux.compiler.optimizer.ssa`，M15）：
  - 线性 IR → CFG（Label/Goto/Branch 基本块，结构化 Try 为不透明节点）；
  - Cooper-Harvey-Kennedy 支配树 + 支配边界，Cytron φ 插入 + 先序重命名（版本局部独占槽位）；
  - **SCCP**（稀疏条件常量传播）：全局常量传播 + 常量分支折叠 + 不可达边移除；
  - 拷贝传播 + 平凡 φ 消除（被 Try 写入的原局部排除）；
  - 激进 DCE（SSA 使用链活性传播）；
  - φ 销毁：落地为前驱拷贝（Branch 前驱边分裂、并行拷贝环两阶段临时），线性化回退。
  - 保守回退：Try 子列表向闭包外层逃逸（break/continue 跨 try）或含 Await 的方法交回最小优化。
- **最小优化**（`BasicOpt`，T-M4-5）：常量折叠、死代码消除、无括号调用内联、空守卫折叠。
- 逃逸分析标注（供 §01-8.6 `stack` 使用，JVM 上映射为分配提示）列为未来。

---

# 9. JVM 后端（ASM）

## 9.1 映射规则总表

| Yux 结构 | JVM 产物 |
|---|---|
| 顶级对象 `Player {}` | `public class Player` |
| 顶级函数 | 文件类的 `public static` 方法（`Main.kt` 文件类等价物） |
| 属性 `name:String` | 私有字段 + `getName()` / `setName()`（Boolean→`isName()`） |
| 自定义访问器 | 对应 getter/setter 方法体 |
| `data` 类 | 构造器 + `equals/hashCode/toString` + 序列化辅助 |
| `service` | 类 + `@YuxService` 注解 |
| `List String` | 泛型擦除 + `Signature`（S-4.3.4） |
| 可空类型 | 运行时为 JVM 引用；基本类型拆箱为 `Integer` 等 |
| Lambda | `invokedynamic`（`LambdaMetafactory`），捕获用 `SerializedLambda` |
| `async fun`/`async{}` | 自研状态机类 `Owner$name$Sm`（实现 `yux.async.Continuation`/`Suspendable`）：`AsyncCpsLowering` 在 IRGen 后把方法体划分为状态块，局部提升为字段，`invokeSuspend()` 按 `state` 字段分派；门面返回 `Task` + 挂起入口续延传递（T-M14，自研运行时，不依赖 kotlinx.coroutines） |
| 注解 | JVM 注解字节码 |

## 9.2 ASM 用法

- 使用 `org.ow2.asm:asm` + `asm-commons`。
- 遍历 `IrModule` → 对每个 `IrClass` 生成 `ClassWriter`（`COMPUTE_FRAMES`）。
- 方法体：遍历 `IrStmt`/`IrExpr` 发射 `MethodVisitor` 指令。
- 源码调试：输出 `LineNumberTable`（由 `SourceSpan` 映射）；可选 `LocalVariableTable` 与源码名参数（`-parameters`）。

## 9.3 插值/String 拼接

`StringTemplate` → `StringBuilder.append` 序列（S-7.6.1）。

## 9.4 空安全守卫

`NullGuard` → `ifnonnull/ifnull` + 分支，null 分支产生类型默认值（`0`、`false`、`null`）或跳转（语句短路）。

## 9.5 与 Java/Kotlin 互操作

- 方法/字段名遵循 JVM 规范（`getName`），保证 Java/Kotlin 直接调用（§01-8.5）。
- 属性映射 JavaBean（§01-8.5.2）：调用 `getX()` 的属性语法生成 `getX()` 调用。

---

# 10. 编译器插件 API

> 这是 YuxPlugin SDK（`03`）的扩展基础，也是 §01-9 的实现。

## 10.1 插件模型

```kotlin
interface YuxCompilerPlugin {
    val id: String
    val version: String

    fun configure(context: PluginContext)
}

interface PluginContext {
    fun registerKeyword(keyword: String, parser: ExtensionParser)
    fun registerSyntaxTransform(transform: SyntaxTransform)
    fun registerSemanticRule(rule: SemanticRule)
    fun registerCodegenHook(hook: CodegenHook)
    fun addRuntimeDependency(module: String)
}
```

## 10.2 扩展能力

1. **扩展关键字** `registerKeyword`：
   - 声明关键字，并给出该语法产生式的解析函数（`ExtensionParser: (Parser, Token) -> ExtensionNode`）。
   - 如 `event` 由 minecraft 扩展注册（见 03）。
2. **语法下沉** `registerSyntaxTransform`：
   - 将 `ExtensionNode` 变换为普通 AST（`event X(..){}` → 类声明 + 注解函数 + 注册代码），在 Lowering 阶段执行。
3. **语义规则** `registerSemanticRule`：领域约束检查。
4. **代码生成钩子** `registerCodegenHook`：
   - 在 JVM 后端生成阶段附加产物（如 minecraft 扩展生成 `plugin.yml`、`Main.class` 骨架）。
5. **运行时依赖** `addRuntimeDependency`：向生成的 Gradle 构建文件注入依赖坐标。

## 10.3 插件加载

- CLI 通过 `--plugin <jar>` 加载；`build.yml` 的 `plugins:` 节声明（§11）。
- 插件用 `ServiceLoader` 注册 `YuxCompilerPlugin` 实现。
- 插件仅能访问 `yux-compiler-plugin-api` 公开类型（不依赖内部实现）。

## 10.4 约束

- 扩展关键字必须唯一；冲突时报错。
- 扩展节点必须在 Lowering 前全部下沉，后端不可见领域语法。
- 禁止插件修改内置语法（隔离性，防止生态分裂）。

---

# 11. 构建管线（build.yml → Gradle）

## 11.1 流程（ADR-12）

```
build.yml
   │  yuxc build
   ▼
┌──────────────────┐   ┌────────────────────────┐
│ build.yml 解析器  │──►│ Gradle 工程生成器        │
└──────────────────┘   │  build.gradle.kts +     │
                       │  settings.gradle +      │
                       │  src 布局               │
                       └───────────┬────────────┘
                                   ▼
                           调用 Gradle（托管运行）
                                   ▼
                            产出 jar / 插件产物
```

## 11.2 build.yml 语义

```yaml
name: MyServer
version: 1.0

language:
  yux: 1.0

target:
  jvm: 21

source:
  main: src            # Yux 源码目录（main.yux 等）
  kotlin: kotlin       # 可选：Kotlin 源码目录（混合项目，见 05）
  java: java           # 可选：Java 源码目录

dependencies:
  minecraft:
    paper: 1.21
  maven:                # 额外仓库坐标
    - group:org.example
      artifact:lib
      version:1.0

plugins:
  serialization: true
  injection: true
  minecraft: true       # 启用 YuxPlugin 扩展（03）
```

## 11.3 生成的 Gradle 脚本（示意）

```kotlin
// build.gradle.kts（由 yuxc 生成）
plugins {
    java
    kotlin("jvm") version "2.0.x"    // 仅当含 Kotlin 源码
    application
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.bukkit:paper-api:1.21-R0.1-SNAPSHOT")
    implementation("yux:yux-core:1.0")
    implementation("yux:yux-minecraft-runtime:1.0")
}
```

- 编译顺序：Yux 源码由 `yuxc` 先编译为 `.class`，再连同 Java/Kotlin 源码交由 Gradle 打包（混合项目细节见 `05`）。
- 生成的构建脚本仅作产物，可被 Git 忽略（`build/`）。

---

# 12. 错误诊断与增量编译

## 12.1 诊断格式

```
<file>:<line>:<col>: error[E0301]: 类型不匹配
  期望: Int
  实际: String
  --> src/main.yux:12:3
  11 | player.inventory.add item
  12 | name = player.health + 1
     |                   ^^^ 这里
```

## 12.2 诊断分级

| 级别 | 处理 |
|---|---|
| `ERROR` | 终止对应文件编译 |
| `WARNING` | 编译继续，汇总输出 |
| `REMIND` | 空安全/风格提示（可 `-A` 关闭） |

## 12.3 增量编译

- 产物缓存：`build/yux-cache/`，以「源码哈希 + 依赖图版本」为键。
- 仅重新编译变更文件及其反向依赖；结果供 Gradle 增量打包。
- v0.1 为「文件级」增量；类级增量列为未来优化。

---

# 13. 未来后端预留（Native / WASM）

## 13.1 预留边界

- 后端以 `Backend` 接口隔离：

```kotlin
interface Backend {
    fun generate(module: IrModule, options: BackendOptions): List<OutputArtifact>
}
```

- IR 设计（§8）避免依赖 JVM 细节（使用独立 `IrType`，不做 `ClassDesc` 泄漏到 IR），为 LLVM/WASM 后端预留。

## 13.2 路线

```
Yux → Yux IR → LLVM IR → Native（Android/iOS/Desktop）
Yux → Yux IR → WASM
```

- `stack`/`unsafe`/`native fun`（§01-8.6）在 Native 后端完整落地；JVM 后端保持「分配提示 + 反射兼容」语义。

---

# 14. 源码目录结构

```
yux/
├── settings.gradle.kts
├── build.gradle.kts
├── yux-compiler/
│   ├── yux-compiler-core/src/main/kotlin/yux/compiler/
│   │   ├── lexer/            # Token, Lexer, Trivia
│   │   ├── parser/           # CstToAst, Pratt, RecursiveDescent
│   │   ├── ast/              # YxNode 层次
│   │   ├── sema/             # SymbolTable, TypeChecker, Inference, SmartCast
│   │   ├── lowering/         # Lowering, NullGuardInsertion
│   │   ├── ir/               # IrModule/IrClass/IrMethod/IrStmt/IrExpr
│   │   └── diag/             # Diagnostic, DiagnosticSink
│   ├── yux-compiler-backend-jvm/src/main/kotlin/yux/backend/jvm/
│   │   ├── AsmBackend.kt
│   │   ├── AsmEmitter.kt
│   │   └── AsmPluginHooks.kt
│   ├── yux-compiler-cli/src/main/kotlin/yux/cli/
│   │   └── Main.kt           # yuxc build / yuxc run / yuxc test
│   └── yux-compiler-plugin-api/src/main/kotlin/yux/plugin/
│       └── api/              # YuxCompilerPlugin, PluginContext, 扩展类型
├── yux-plugin-minecraft/      # （见 03）
├── yux-stdlib/                # yux.core / yux.collection / ...
└── build-tool/                # BuildYmlParser, GradleGenerator
```

## 命令行示例

```
yuxc build            # 读取 build.yml，编译并调用 Gradle 打包
yuxc build --plugin yux-plugin-minecraft.jar
yuxc run              # 编译并运行
yuxc test             # 编译并执行 yux.test 测试
yuxc repl             # 交互式（未来）
```

---

# 附录：与 01 文档的交叉引用

| 本文章节 | 依据 |
|---|---|
| §4 词法 | 01-§2 |
| §5 语法 | 01-§3,§6,§7 |
| §6 消歧 | 01-§7.7 |
| §7 语义 | 01-§4.5,§5,§6,§8 |
| §8 IR | 01-§4,§8 |
| §9 后端 | 01-§5,§8.5 |
| §10 插件 | 01-§9 |
| §11 构建 | 00-ADR-12, 01-§10.9 |
