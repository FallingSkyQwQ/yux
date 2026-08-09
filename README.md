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

# M8 YuxPlugin SDK：--plugin 加载 minecraft 扩展，编译 event/command/config 语法
./gradlew :yux-plugin-minecraft:yux-compiler-minecraft:jar
./gradlew :yux-compiler:yux-compiler-cli:run --args="build -p samples/minecraft-hello --plugin yux-plugin-minecraft/yux-compiler-minecraft/build/libs/yux-compiler-minecraft-0.1.0-SNAPSHOT.jar"
# 期望: 构建成功 + samples/minecraft-hello/build/libs/minecraft-hello-1.0.0.jar（含 plugin.yml + 下沉类）

# M9 Home 插件：--plugin 加载 minecraft 扩展，编译完整 Paper 插件（命令/事件/配置/存储/定时）
./gradlew :yux-compiler:yux-compiler-cli:installDist
./samples/home-plugin/verify.sh
# 期望: yuxc build/test -p samples/home-plugin 通过 + jar 含 18 个下沉类 + plugin.yml（04-§10）

# M10 混合项目：Yux + Kotlin + Java 三方互操作（Kotlin 格式化 + Java 日志 + Yux 输出）
./yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc build -p samples/mixed
./yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc run  -p samples/mixed
# 期望: 构建产出 MixedServer-1.0.0.jar（含三语言类）；运行输出余额系统三方协作结果（05-§9）
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
| M8 YuxPlugin SDK | ✅ | 注解契约 @YuxEvent/@YuxCommand/@YuxConfig/@YuxTask（T-M8-1）+ 语言扩展下沉 plugin/event/command/config/permission/task（T-M8-2..6）+ PluginBootstrap 扫描注册（T-M8-7）+ ConfigManager（T-M8-8）+ NBT 序列化（T-M8-9）+ TaskScheduler（T-M8-10）+ ItemBuilder/PlayerUtil/LocationUtil（T-M8-11）+ plugin.yml 生成（T-M8-12） |
| M9 Paper 示例 | ✅ | Home 插件 samples/home-plugin（T-M9-1，04 全篇：4 命令 + tab + 3 事件 + 配置 + YAML 落盘 + 冷却 + 定时保存）+ 编译器扩展（扩展函数/索引/if 表达式/顶层属性/泛型构造/字符串拼接/JVM 互操作）+ HomePluginE2eTest 集成测试（T-M9-3）+ verify.sh（T-M9-2）+ Java 对照 samples/java-equivalent（T-M9-4，行数对比） |
| M10 混合项目 | ✅ | 三方互操作 samples/mixed（T-M10-1/3，05-§9 余额系统：Yux→Kotlin/Java）+ 编译顺序编排（T-M10-2，compileMixed 双向自适应：Yux→Java/Kotlin 先 gradle classes；Kotlin/Java→Yux 先 Yux）+ 编译器 classLoader 注入 + 生成脚本（仓库声明/kotlin 工具链/files() 依赖）+ MixedProjectE2eTest（含反向互操作） |

## 文档索引

- [`docs/00-总览.md`](docs/00-总览.md) — 路线图与 ADR- [`docs/01-Yux-语法规范.md`](docs/01-Yux-语法规范.md) — 完整语法规范
- [`docs/02-Yux-编译器架构.md`](docs/02-Yux-编译器架构.md) — 编译器架构
- [`docs/03-YuxPlugin-SDK-设计.md`](docs/03-YuxPlugin-SDK-设计.md) — SDK 设计
- [`docs/06-开发计划.md`](docs/06-开发计划.md) — 里程碑开发计划
- [`AGENTS.md`](AGENTS.md) — 开发与 Git 协作约定

## License

Apache License 2.0 — 见 [`LICENSE`](LICENSE)。
