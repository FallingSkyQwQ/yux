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
./gradlew :yux-compiler:yux-compiler-cli:run --args="run <file.yux>"   # M5 运行（编译 + 执行 main）

# M6 插件：--plugin <jar> 加载编译器插件（扩展关键字 → 解析 → 下沉 → 字节码）
./gradlew :yux-compiler:yux-compiler-cli:run --args="run --plugin samples/extension/build/libs/extension-0.1.0-SNAPSHOT.jar samples/extension/greet.yux"
# 期望输出: Hello, Yux plugin!

# M7 构建管线：yuxc build -p <项目目录>（build.yml → Yux 编译 → Gradle 打包 → build/libs/*.jar）
./gradlew :yux-compiler:yux-compiler-cli:run --args="build -p samples/helloworld"
# 期望: 构建成功 + samples/helloworld/build/libs/helloworld-0.1.0.jar
```

## 里程碑状态

| 里程碑 | 状态 | 交付物 |
|---|---|---|
| M0 工程骨架 + CI | ✅ | 多模块 Gradle 工程、lint、CI |
| M1 词法分析器 | ✅ | Token/Trivia/插值分词 + golden 测试 |
| M2 语法分析器 | ✅ | CST/Pratt 表达式/消歧/错误恢复 + CstToAst + golden `.ast` |
| M3 语义分析 | ✅ | 类型系统/符号表/类型检查/推断/智能转型/空守卫 + 86 负向用例 + `.sema` golden |
| M4 IR | ✅ | IrModule/IrStmt/IrExpr/IrType + IRGen + 最小优化 + golden `.ir` + `yuxc ir` |
| M5 JVM 后端 | ✅ | ASM 发射（类/属性/data/Lambda/注解/互操作/空守卫）+ `yuxc run` + samples 端到端 |
| M6 编译器插件 API | ✅ | 插件 SPI（`YuxCompilerPlugin`/`PluginContext`/`ExtensionParser`）+ `--plugin` 加载 + 下沉管线 + hello-extension 示例 |
| M7 构建管线 | ✅ | build.yml → BuildConfig 解析（T-M7-1）+ Gradle 脚本生成（T-M7-2）+ 编译顺序编排与 yux-symbols.json（T-M7-3/7）+ 托管 Gradle 打包（T-M7-4）+ yuxc build/run/test（T-M7-5）+ 文件级增量缓存（T-M7-6） |

## 文档索引

- [`docs/00-总览.md`](docs/00-总览.md) — 路线图与 ADR
- [`docs/01-Yux-语法规范.md`](docs/01-Yux-语法规范.md) — 完整语法规范
- [`docs/02-Yux-编译器架构.md`](docs/02-Yux-编译器架构.md) — 编译器架构
- [`docs/03-YuxPlugin-SDK-设计.md`](docs/03-YuxPlugin-SDK-设计.md) — SDK 设计
- [`docs/06-开发计划.md`](docs/06-开发计划.md) — 里程碑开发计划
- [`AGENTS.md`](AGENTS.md) — 开发与 Git 协作约定

## License

Apache License 2.0 — 见 [`LICENSE`](LICENSE)。
