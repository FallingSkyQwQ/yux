# Yux Language

> **Simple · Handy · Powerful** — JVM First 的现代通用语言。

Yux 隐藏复杂性，让普通开发者拥有极高生产力；保留 JVM 生态，让高级开发者拥有无限可能。

本仓库为 Yux 语言编译器（`yuxc`）与 YuxPlugin SDK 的实现仓库。
详细设计见 [`docs/`](docs/)（00~06 分篇设计文档）与 [`Design.md`](Design.md)。

## 项目结构

```
yux/
├── yux-compiler/               # 核心编译器（Kotlin 多模块）
│   ├── yux-compiler-core/      # Lexer / Parser / AST / Sema / IR / diag
│   ├── yux-compiler-backend-jvm/# JVM 后端（ASM）
│   ├── yux-compiler-plugin-api/# 编译器插件 SPI
│   ├── yux-compiler-cli/       # 命令行入口 yuxc
│   └── yux-test-support/       # golden 快照 + 测试基座
├── yux-plugin-minecraft/       # YuxPlugin SDK（语言扩展 + 运行时）
├── yux-stdlib/                 # 标准库 yux.core / collection / io / ...
├── build-tool/                 # build.yml → Gradle 生成器
└── docs/                       # 设计文档 00~06
```

## 快速开始（开发）

需要 JDK 21+。

```bash
./gradlew build        # 全量构建 + 测试
./gradlew lint         # detekt 规范检查
./gradlew projects     # 列出全部模块

# 里程碑命令
./gradlew :yux-compiler:yux-compiler-cli:run --args="lex <file.yux>"   # M1 词法
./gradlew :yux-compiler:yux-compiler-cli:run --args="ast <file.yux>"   # M2 语法
./gradlew :yux-compiler:yux-compiler-cli:run --args="check <file.yux>" # M3 语义
./gradlew :yux-compiler:yux-compiler-cli:run --args="ir <file.yux>"    # M4 IR
```

## 里程碑状态

| 里程碑 | 状态 | 交付物 |
|---|---|---|
| M0 工程骨架 + CI | ✅ | 多模块 Gradle 工程、lint、CI |
| M1 词法分析器 | ✅ | Token/Trivia/插值分词 + golden 测试 |
| M2 语法分析器 | ✅ | CST/Pratt 表达式/消歧/错误恢复 + CstToAst + golden `.ast` |
| M3 语义分析 | ✅ | 类型系统/符号表/类型检查/推断/智能转型/空守卫 + 86 负向用例 + `.sema` golden |
| M4 IR | ✅ | IrModule/IrStmt/IrExpr/IrType + IRGen + 最小优化 + golden `.ir` + `yuxc ir` |
| M5+ | ⏳ | 见 [`docs/06-开发计划.md`](docs/06-开发计划.md) |

## 文档索引

- [`docs/00-总览.md`](docs/00-总览.md) — 路线图与 ADR
- [`docs/01-Yux-语法规范.md`](docs/01-Yux-语法规范.md) — 完整语法规范
- [`docs/02-Yux-编译器架构.md`](docs/02-Yux-编译器架构.md) — 编译器架构
- [`docs/03-YuxPlugin-SDK-设计.md`](docs/03-YuxPlugin-SDK-设计.md) — SDK 设计
- [`docs/06-开发计划.md`](docs/06-开发计划.md) — 里程碑开发计划
- [`AGENTS.md`](AGENTS.md) — 开发与 Git 协作约定

## License

Apache License 2.0 — 见 [`LICENSE`](LICENSE)。
