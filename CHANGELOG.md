# Changelog

本文件记录各里程碑的显著变更（含 breaking changes，见 06-§3）。

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
