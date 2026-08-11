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
# 一键安装（Linux/macOS/WSL，JDK 21+）：安装到 ~/.yux/bin，幂等
curl -fsSL https://raw.githubusercontent.com/FallingSkyQwQ/yux/main/setupyux.sh | bash

export PATH="$HOME/.yux/bin:$PATH"
yuxc run samples/tutorial/01-intro/02-hello.yux   # Hello Yux

# 源码构建开发：
./gradlew build        # 全量构建 + 测试
./gradlew lint         # detekt 规范检查
./gradlew projects     # 列出全部模块

# 里程碑命令（installDist 后直接用 yuxc；开发时也可用 gradle run 转发）
./gradlew :yux-compiler:yux-compiler-cli:installDist
YUXC=./yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc
$YUXC lex <file.yux>        # M1 词法
$YUXC ast <file.yux>        # M2 语法
$YUXC check <file.yux>      # M3 语义（彩色诊断 + 源码标注）
$YUXC ir <file.yux>         # M4 IR
$YUXC run <file.yux>        # M5 运行（编译 + 执行 main）
$YUXC new demo              # M16 脚手架：hello/project/plugin 模板
$YUXC fmt samples/hello.yux # M16 格式化（-i 原地 / --check 校验 / stdin）
$YUXC version               # M16 版本
$YUXC --completion bash     # M16 shell 补全
$YUXC --help                # 全部命令与选项

# M6 插件：--plugin <jar> 加载编译器插件（扩展关键字 → 解析 → 下沉 → 字节码）
$YUXC run --plugin samples/extension/build/libs/extension-0.1.0-SNAPSHOT.jar samples/extension/greet.yux
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

# M11 标准库：yux.collection/yux.core/yux.async（成员调用降级为 stdlib 静态调用）
./yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc run samples/stdlib.yux
# 期望输出: 60.030306030.012.0ABCabc3HELLOyux=42async!done（map/filter/sum/uppercase/split/format/launch）
./bench/run-bench.sh --out bench/RESULTS.md   # M11 基准复跑

# M14 async 协程：async fun / async{} / await（完整 CPS 状态机）
./yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc run samples/async.yux
# 期望输出: 42 8 caught（见 samples/async.stdout）
```

## 里程碑状态

| 里程碑 | 状态 | 交付物 |
| --- | --- | --- |
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
| M11 标准库完善 | ✅ | yux.io.IO / yux.net.Http（T-M11-1）+ yux.async Task/Tasks（T-M11-2，launch/parallel/await/sleep 真并发）+ yux.collection.Colls / yux.core.Text（T-M11-3）+ 编译器成员调用降级（JvmExtensions 注册表 `xs.map {}`/`s.uppercase()` + FunctionN 桥接 + Unit-Lambda 修复）+ fuzz 5000 例（T-M11-4）+ bench/ 基准（T-M11-5）+ core 覆盖率 ≥70% 门禁（T-M11-6，实际 84.3%）+ samples/stdlib 端到端样例 |
| M14 async 协程 | ✅ | `async fun`/`async{}` 完整 CPS 状态机（T-M14-1..7，R3 转正）：`await` 软关键字 + 双 ABI（async 上下文挂起调用 / 同步门面返回 Task）+ `yux.async.Continuation`/`Suspendable`/`Continuations` 运行时 + try/catch/finally 跨挂起点 + await 挂起可取消 + CancellationException 自动重抛 + CPS golden 套件 + AsyncFunE2e 15 例（含深循环栈安全、递归链、async{} 内 await）+ `parallel{}` 保持 ForkJoinPool |
| M15 完整 SSA 优化器 | ✅ | 完整 SSA 优化框架（T-M15-1..7，02-§8.4 后置转正）：CFG + 支配树/支配边界 + Cytron φ 插入/重命名 + SCCP 全局常量传播（常量分支折叠 + 死边移除）+ 拷贝传播/平凡 φ 消除 + 激进 DCE + φ 销毁（边分裂拷贝）；结构化 Try 不透明节点 + flush 拷贝 + 原寄存器回退；break/continue 跨 try 保守回退 BasicOpt；`SsaOptTest` 12 例 + `SsaE2eTest` 6 例 + 覆盖率 82.0% |
| M12 发布 + 教程 | ✅ | 入门到精通语言圣经 `docs/tutorial/`（6 篇，锁 v0.1.0-m15）+ golden 示例 `samples/tutorial/` 74 例（62 正例 .stdout + 12 反例 .err，`TutorialSamplesTest` 逐字节比对）+ 参数默认值 IRGen 修复（S-5.5.5）+ `setupyux.sh` 一键安装 + `scripts/check-tutorial-docs.sh` 文档↔示例一致性校验 |
| M16 实用 CLI | ✅ | Clikt 4.4 重写（T-M16-1..5）：声明式子命令 + 自动 help/usage + `--completion` shell 补全；`yuxc new` 脚手架（hello/project/plugin 模板）；`yuxc fmt` 格式化器（CST 驱动、幂等、注释保留、插件块 raw copy）；彩色诊断 + 源码插入符标注（tty 检测 + `--color`）；构建生成版本号 `yuxc version`；`yuxc doc`/`yuxc help`；核心 + CLI 756 测试全绿 |

## 文档索引

- [`docs/tutorial/`](docs/tutorial/00-总览.md) — 入门到精通语言圣经（活文档 + golden 示例，锁 v0.1.0-m15）
- [`docs/00-总览.md`](docs/00-总览.md) — 路线图与 ADR
- [`docs/01-Yux-语法规范.md`](docs/01-Yux-语法规范.md) — 完整语法规范
- [`docs/02-Yux-编译器架构.md`](docs/02-Yux-编译器架构.md) — 编译器架构
- [`docs/03-YuxPlugin-SDK-设计.md`](docs/03-YuxPlugin-SDK-设计.md) — SDK 设计
- [`docs/06-开发计划.md`](docs/06-开发计划.md) — 里程碑开发计划
- [`AGENTS.md`](AGENTS.md) — 开发与 Git 协作约定

## License

Apache License 2.0 — 见 [`LICENSE`](LICENSE)。
