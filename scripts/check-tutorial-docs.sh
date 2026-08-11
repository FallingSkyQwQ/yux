#!/usr/bin/env bash
#
# check-tutorial-docs.sh — 校验 docs/tutorial/*.md 与 samples/tutorial/ 的一致性。
#
# 规则：
#   1. 文档中出现的 `samples/tutorial/...yux` 引用必须真实存在；
#   2. 每个被引用的示例必须有且仅有一个快照（.stdout 或 .err）；
#   3. docs/tutorial/ 与 samples/tutorial/ 的篇章目录一一对应。
#
# 用法：bash scripts/check-tutorial-docs.sh
# 退出码：0 = 通过；1 = 有不一致项（输出明细）。
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCS_DIR="$ROOT/docs/tutorial"
SAMPLES_DIR="$ROOT/samples/tutorial"
FAIL=0

[ -d "$DOCS_DIR" ] || { echo "缺少 docs/tutorial/"; exit 1; }
[ -d "$SAMPLES_DIR" ] || { echo "缺少 samples/tutorial/"; exit 1; }

refs="$(grep -rhoE 'samples/tutorial/[A-Za-z0-9_./-]+\.yux' "$DOCS_DIR"/*.md | sort -u)"
if [ -z "$refs" ]; then
    echo "警告: docs/tutorial/ 未引用任何 samples/tutorial/*.yux"
fi
while read -r ref; do
    [ -z "$ref" ] && continue
    file="$ROOT/$ref"
    if [ ! -f "$file" ]; then
        echo "缺失: 文档引用的示例不存在 → $ref"
        FAIL=1
        continue
    fi
    stem="${file%.yux}"
    has_stdout=0; has_err=0
    [ -f "$stem.stdout" ] && has_stdout=1
    [ -f "$stem.err" ] && has_err=1
    if [ $((has_stdout + has_err)) -ne 1 ]; then
        echo "快照: $ref 应有且仅有一个 .stdout/.err 快照（当前 stdout=$has_stdout err=$has_err）"
        FAIL=1
    fi
done <<< "$refs"

# 篇章目录一一对应
for doc in "$DOCS_DIR"/*.md; do
    base="$(basename "$doc" .md)"
    # 00-总览 / 05-附录 不对应示例目录；跳过
    case "$base" in
        00-*) continue ;;
        05-*) continue ;;
    esac
    # 01-入门 → 01-intro 映射
    case "$base" in
        01-入门) dir="01-intro" ;;
        02-进阶) dir="02-advance" ;;
        03-精通) dir="03-master" ;;
        04-生态门户) dir="04-ecosystem" ;;
        *) continue ;;
    esac
    if [ ! -d "$SAMPLES_DIR/$dir" ]; then
        echo "缺失: 文档篇 $base 对应示例目录 samples/tutorial/$dir/"
        FAIL=1
    fi
done

# 未被任何文档引用的示例（提醒，不算失败）
referenced="$(grep -rhoE 'samples/tutorial/[A-Za-z0-9_./-]+\.yux' "$DOCS_DIR"/*.md | sort -u)"
if [ "$FAIL" -eq 0 ]; then
    echo "OK: docs/tutorial ↔ samples/tutorial 一致（引用 $(echo "$referenced" | grep -c . || echo 0) 处示例）"
else
    echo "FAIL: 存在不一致项"
fi
exit "$FAIL"
