# Changelog

本文件记录各里程碑的显著变更（含 breaking changes，见 06-§3）。

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
