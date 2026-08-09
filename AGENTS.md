# AGENTS.md

本文件是仓库开发约定的权威入口，Codex 与协作者统一遵守。严格 Git Workflow 的完整定义见 [`docs/06-开发计划.md`](docs/06-开发计划.md) §7.4（M5 起强制）。

## 分支：开发统一在 feature 分支

- 所有开发统一在当前里程碑 feature 分支上进行（当前：`feature/m11-stdlib`）。
- 禁止在 `main` 上直接开发、提交或 push；`main` 仅接受 PR 合入。
- 每个里程碑只保留一个 `feature/<milestone>` 分支；禁止再创建 `m<里程碑>-<主题>` 临时分支。
- 旧分支在合入后立即删除（本地 + 远端）；日常只保留当前 feature 分支与 `origin/main` 跟踪。

## 提交规范

- 提交信息以 `T-<里程碑>-<任务>` 开头（如 `T-M5-1: ...`），或使用 `fix:`/`docs:` 等 conventional commit 风格。
- 每个提交/PR 必须可构建且测试通过；测试、golden 快照、CHANGELOG 随变更一起落地。
- 禁止对共享 feature 分支 force push；需要改写历史时先与用户/团队确认。

## PR 与评审

- PR 按里程碑粒度拆分，目标一律为 `main`。
- 必须通过 CI（`./gradlew build lint` + 覆盖率门禁）与 CodeRabbit/人工评审。
- 诊断文本或 golden 快照变更必须同步更新快照，并在 PR 中说明。
- 合并后的旧分支立即删除（本地 + 远端）。

## 文档同步

- 里程碑完成后更新 `docs/06-开发计划.md`、`README.md`、`CHANGELOG.md`。
