# Changelog

本文件记录各里程碑的显著变更（含 breaking changes，见 06-§3）。

## [v0.1.0-m4] - 2026-08-08 — M4 IR

### 新增
- `yux.compiler.ir`（T-M4-1/2/3，02-§8）：
  - `IrType` 类型系统（T-M4-3）：JVM 无关（02-§13.1，不用 ClassDesc），Basic/Nullable/Generic/Function/Declared/TypeParam/Void/Nothing/Error，`render()`/`equivalent()`（符号按名等价）与 `defaultValue()`（空守卫默认值）；`SemaType → IrType` 桥接（`irgen/TypeBridge`）；
  - `IrModule` 层次（T-M4-1）：`IrModule → IrClass（含文件类，05-§5.3）→ IrField/IrProperty/IrMethod`；属性 = backing 字段 + getter/setter（Boolean→`isX`，01-§5.2）；`<init>` 构造器（实参 = 属性顺序）、`$clinit`（顶层属性初始化）；
  - `IrStmt`/`IrExpr`（T-M4-2）：02-§8.2 全集（LocalAssign/Call/New/FieldAccess/Branch/Goto/Return/Throw/Monitor/TryCatch；Const/LocalRead/FieldRead/Arith/Compare/Convert/Invoke/StringTemplate/NullGuard/Lambda）+ 必要补充（Label 定位点、表达式位置 New、Not/Neg、IsType）；`inferType()` 推导表达式类型；调用目标统一为 `IrCallable`（`IrMethodRef` Yux 方法 / `IrJvmCall` JVM 互操作）。
- `yux.compiler.irgen`（T-M4-4）：`IRGen(analysis).generate(declsByFile)` 两遍式（骨架遍 + 主体遍）；
  - 下沉：if→Branch/Goto、while→Branch、for→迭代器展开（`iterator()/hasNext()/next()`，Range 归属 `yux.core.Range`）、when→分支链（`is T` 用 IsType）、`&&`/`||` 短路、try→结构化 Try、async/parallel/unsafe→同步降级（R3）；
  - 空守卫：M3 插入点（guardPoints）→ `IrExpr.NullGuard` 包裹读取路径（02-§9.4）；
  - 互操作（`JvmCallResolver`）：镜像 TypeChecker 的 jvmForBasic/JavaBean 访问器/静态成员映射，`print/println/serialize` 内置函数 → `yux.core.CoreLib` 静态调用；
  - Lambda → 合成方法 `lambda$n`（箭头/块/`it`）；字符串插值 → StringTemplate；字面量解码（十六/二进制/`_`/后缀/转义/`\uXXXX`）。
- `yux.compiler.optimizer.BasicOpt`（T-M4-5，02-§8.4 默认开启）：常量折叠（数值提升/字符串拼接/比较/Not/Neg/Convert，除零不折叠）、死代码消除（终结语句后不可达 + 未读取纯赋值，副作用保留）、冗余跳转消除（Goto-紧随-Label、Branch 同目标）、空守卫折叠（接收者静态 null→默认值 / 静态非空→去守卫）；迭代至不动点（≤3 轮）。
- `yux.compiler.ir.IrPrinter`（T-M4-6）：稳定文本 dump（golden 逐字节锁定），`yuxc ir <file>` 子命令（IRGen + BasicOpt 后输出）。
- 测试：60 例新增（IrType 等价/桥接 9、IrStructure 结构 9、IRGen 下沉 13、BasicOpt 优化 12、GoldenIr 快照 5 + CLI 2 例），累计 360 例全绿；golden 快照 `golden/ir/*.ir`（5 个样例：hello/types/controlflow/nullable/interop）。

### 说明
- 文档 02-§8.2 的补充（M4 落地的必要具体化）：`IrStmt.Label`（Branch/Goto 需标签定位）、`IrExpr.New`（表达式位置构造）、`Not/Neg`（`!`/一元负）、`IsType`（`is T`）、`IrStmt.Call` 增加 receiver 槽位（实例方法语句位置调用）、`IrStmt.FieldAccess` 增加 value 槽位（写值表达）。后端（M5）按源/目标类型区分 `Convert`（CHECKCAST vs i2l）。
- `Result(true, 42)` 等泛型构造实参的类型实参推断（`Result Int`）为 M3 既有限制，IR 忠实反映（`Result`）。
- `..=` 复合赋值与函数类型变量调用在 M4 不展开（sema 后置清单）。

## [v0.1.0-m3] - 2026-08-08 — M3 语义分析

### 新增
- `yux.compiler.sema`：
  - `SemaType` 类型系统（T-M3-1）：Basic/Declared/TypeParam/Function/InferenceVar/Unit/Nothing/Error，可空性 + 赋值兼容（`Any` 顶层、`Nothing` 空底）；
  - `SymbolTable` + `FileScope`（T-M3-2）：声明收集遍（02-§6.2），包→类型索引、跨文件/导入/star 导入解析、冲突检测；
  - `ClassPathSymbolProvider`（T-M3-2）：JVM 类反射懒加载 + 缓存（占位原地填充保证 `===` 实例一致），JavaBean 属性/方法/静态成员映射，Java 类型视为可空（S-8.1）；
  - `TypeResolver`（T-M3-1/3）：AST 类型→SemaType，泛型实参数目校验（S-4.3.2）、类型参数作用域（S-4.3.3）、W0001 命名约定回退；
  - `Declarations`（T-M3-3）：两遍式成员解析、data/service/override/注解/访问器校验（E0010/E0012/E0019/E0026）；
  - `Inference`（T-M3-5）：局部双向推断（ADR-08），字面量按目标类型收窄（S-7.5.4）、函数返回推断（S-5.5.2）；
  - `TypeChecker`（T-M3-4）：S-xx 规则全量（条件 Boolean、实参/返回匹配、确定性赋值含分支合并、运算符、成员/静态/构造调用、Lambda、this/super、异常、循环）；
  - `SmartCast`（T-M3-6）：`is T`/空值判空分支智能转型，可变引用抑制 + R0002；
  - `Annotations`（T-M3-8/9）：注解目标校验（S-8.2）、service 注入图循环检测（S-5.4.1）；
- `yux.compiler.lowering.NullGuard`（T-M3-7）：可空接收者读路径守卫插入点 + R0001，写路径 E0023（S-8.1）；
- `yux.compiler.diag.ErrorCodes.md`（T-M3-10）：诊断码唯一事实源，`ErrorCodes.kt` 常量（E0001~E0028/W0001/R0001~R0002）；
- 内置函数 `print/println/serialize`（yux.core 最小集占位，标准库未实现前供示例通过）；
- `yuxc check` 子命令：输出类型推导 + 诊断；
- 测试：115 例（类型系统/符号表/类型检查/推断/智能转型/空守卫/注解 + **86 个编译期负向用例**，M3 验收要求 ≥80）+ `golden/sema/*.sema` 快照（5 个样例）。

### 说明
- 泛型实参数目：解析器层已拒绝过度实参（M2 消歧收集元数），sema 侧覆盖放行后仍不一致的情形（如 `Map String`）。
- 类属性默认可变（自动 get/set，S-5.2.1）；`data` 类属性默认只读（S-5.3.1），可自定义 setter 覆盖。
- `E0011/E0025/E0027` 为预留诊断码（data 非法成员/重复 override/歧义引用），由解析器或后续里程碑结构性保证。

## [v0.1.0-m2] - 2026-08-08 — M2 语法分析器

### 新增
- `yux.compiler.ast`：`YxNode` 层次（声明/类型/语句/表达式，节点携带 `SourceSpan`）——M3 语义分析的稳定输入。
- `yux.compiler.parser`：
  - `Cst`：全产生式 CST 节点（含括号/换行占位）；
  - `Parser`：手写递归下降，package/import/类/data/service/函数/属性/访问器/注解/语句；
  - `PrattParser`：01-§7.1 优先级表 + 后缀链 + 无括号单参调用（S-7.2.2，`f a + b`==`f(a)+b`）；
  - `LambdaParser`：箭头/多参括号/块 Lambda（隐式 `it`）；
  - `Disambiguation`：两遍式声明预收集（类型/函数/属性 + 泛型元数），`Type(args)` 构造 vs `func(args)` 调用 vs 静态成员访问（01-§7.7）；
  - `Recovery`：同步恢复（缺 `)`/`}`/坏 token 不抛异常，多错误收集）；
  - `CstToAst` + `AstPrinter`：规约 + 稳定 dump。
- `yux.compiler.lexer`：补上语法必需的 `throw` 关键字（01-§2.4 列表漏列，实际 31 个基础关键字）。
- golden 快照：`parser-samples/*.yux` → `golden/ast/*.ast`（8 个样例，覆盖声明/语句/表达式/消歧/Lambda/互操作）。
- `yuxc` CLI：`lex` / `ast` 子命令（`yux-compiler-cli`）。

### 说明
- 已知歧义（记录供后续处理）：单行 `if x y`（条件与单语句体均以 Primary 起始）在无括号调用语义下解析为 `if(x(y))`，单行体建议用块或 `return/break` 形式。

## [v0.1.0-m1] - 2026-08-07 — M1 词法分析器

### 新增
- `yux.compiler.lexer`：`Lexer`、`TokenKind`（与 01-§2.6 运算符表一一对应）、`Token`、`SourcePosition`、`Trivia`、`ScannerState`、`InterpolationScanner`、`TokenPrinter`。
- `yux.compiler.diag`：`Severity`（ERROR/WARNING/REMIND）、`Diagnostic`、`DiagnosticSink`（坏输入不抛异常）。
- `yux.compiler.source`：`SourceFile`。
- 词法能力：30 基础关键字 + 5 软关键字；十/十六/二进制与 `_`/后缀数字；浮点与指数；字符/字符串转义与 `\$`；`"""` 原始串；`$name`/`${expr}` 插值分词（括号平衡、嵌套字符串）；行/块（可嵌套）/文档注释；换行折叠为单个 `NEWLINE`。
- golden 快照：`samples/*.yux` → `golden/tokens/*.tokens`（7 个样例，覆盖关键字/数字/字符串/运算符/注释/插值/HelloWorld）。

## [v0.1.0-m0] - 2026-08-07 — M0 工程骨架 + CI

### 新增
- 初始化 Git 仓库与多模块 Gradle 工程（09 个模块，对应 02-§14 / 06-§9.1）：
  - `yux-compiler`：core / backend-jvm / plugin-api / cli / test-support
  - `yux-plugin-minecraft`：compiler-minecraft / minecraft-runtime（骨架）
  - `yux-stdlib`、`build-tool`
- 统一版本目录 `gradle/libs.versions.toml`（Kotlin 2.3.21、ASM、JUnit5、coroutines、Jackson、SnakeYAML）。
- 编码规范：detekt 2.0.0-alpha.6（兼容 Gradle 9.6.1 / JDK 25）+ `.editorconfig`；`./gradlew lint` 聚合全部模块。
- CI（`.github/workflows/ci.yml`）：PR 触发 `build + test + lint` + Jacoco 覆盖率门禁（行覆盖 ≥ 60%）。
- 测试基座 `yux-test-support`：`GoldenFile`（golden 快照，`UPDATE_GOLDEN=1` 逃生阀）与 `SourceFile`/`Sources`（内存源文件工具）。
- `README.md`、`LICENSE`（Apache-2.0）、`CHANGELOG.md`。

### 说明
- detekt 采用 `2.0.0-alpha.6`（`dev.detekt`）：稳定版 1.23.8 不支持 JDK 25，且 2.0 alpha 已官方声明针对 Gradle 9.6.1 / JDK 25 构建。
- 编译目标 JDK 21（`jvmToolchain(21)`）；本地需具备 JDK 21（或由 foojay resolver 自动下载）。
