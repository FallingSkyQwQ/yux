#!/usr/bin/env bash
# T-M11-5：编译耗时 / 输出 jar 体积基准（M11 里程碑）
#
# 测量对象（用仓库 installDist 版 yuxc）：
#   1. samples/helloworld、samples/mixed：冷编译（build --clean）×3 + 增量编译 ×3
#   2. samples/hello.yux：`yuxc run`（纯编译 + 运行）×3
#   3. 各工程 build/libs/*.jar 体积
#
# 用法：./bench/run-bench.sh [--out <file.md>]
#   --out  将完整 Markdown 报告（含表头说明）写入文件；stdout 始终输出。
#
# 依赖：bash 内建 + stat/date（CI 常见）；计时优先 /usr/bin/time -f %e，
#       缺失时用 GNU date +%s.%N，再兜底 bash $SECONDS。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OUT_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out)
      OUT_FILE="${2:?--out 需要文件路径}"
      shift 2
      ;;
    *)
      echo "用法: $0 [--out <file.md>]" >&2
      exit 1
      ;;
  esac
done

# ---- 环境探测：JDK（优先 JAVA_HOME，否则探测 21，与 target.jvm 一致）----
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for cand in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21* /usr/lib/jvm/jdk-21* /opt/jdk-21*; do
    if [ -x "$cand/bin/java" ]; then
      export JAVA_HOME="$cand"
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "错误: 找不到 java，请设置 JAVA_HOME 后重试" >&2
  exit 1
fi
export JAVA_HOME
JDK_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
GIT_REV="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git branch --show-current)"
BENCH_DATE="$(date +%F\ %T)"
GRADLE_VERSION="$(./gradlew --version 2>/dev/null | grep -E '^Gradle' | tr -s ' ' | cut -d' ' -f2 || true)"

# ---- 构建 yuxc（installDist）----
echo "== 构建 yuxc（:yux-compiler:yux-compiler-cli:installDist）==" >&2
./gradlew :yux-compiler:yux-compiler-cli:installDist -q
YUXC="${YUXC:-yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc}"
if [ ! -x "$YUXC" ]; then
  echo "错误: $YUXC 不存在，installDist 失败？" >&2
  exit 1
fi

# ---- 计时函数：优先 /usr/bin/time，缺失用 date +%s.%N / $SECONDS ----
time_seconds() {
  local tmp rc start end
  if [ -x /usr/bin/time ]; then
    tmp="$(mktemp)"
    set +e
    /usr/bin/time -f "%e" "$@" >/dev/null 2>"$tmp"
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then cat "$tmp" >&2; rm -f "$tmp"; return $rc; fi
    start="$(tail -1 "$tmp")"
    rm -f "$tmp"
    printf '%s' "$start"
    return 0
  fi
  if date +%s.%N >/dev/null 2>&1; then
    start="$(date +%s.%N)"
    "$@" >/dev/null
    end="$(date +%s.%N)"
    awk -v s="$start" -v e="$end" 'BEGIN{printf "%.3f", e-s}'
  else
    start="$SECONDS"
    "$@" >/dev/null
    awk -v s="$start" -v e="$SECONDS" 'BEGIN{printf "%.1f", e-s}'
  fi
}

# ---- 中位数（3 个数值参数）----
median() {
  printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'
}

# ---- jar 体积（GNU stat，BSD 兜底）----
jar_size() {
  stat -c %s "$1" 2>/dev/null || stat -f %z "$1"
}

# ---- 单工程基准：冷编译 ×3 + 增量 ×3 + jar 体积 ----
bench_project() {
  local name="$1" dir="$2" c1 c2 c3 i1 i2 i3 size jar
  echo "== 工程 $name（$dir）: 冷编译 ×3 ==" >&2
  c1="$(time_seconds "$YUXC" build -p "$dir" --clean)"
  echo "  冷编译 #1: ${c1}s" >&2
  c2="$(time_seconds "$YUXC" build -p "$dir" --clean)"
  echo "  冷编译 #2: ${c2}s" >&2
  c3="$(time_seconds "$YUXC" build -p "$dir" --clean)"
  echo "  冷编译 #3: ${c3}s" >&2
  echo "== 工程 $name（$dir）: 增量编译 ×3（缓存命中）==" >&2
  i1="$(time_seconds "$YUXC" build -p "$dir")"
  echo "  增量编译 #1: ${i1}s" >&2
  i2="$(time_seconds "$YUXC" build -p "$dir")"
  echo "  增量编译 #2: ${i2}s" >&2
  i3="$(time_seconds "$YUXC" build -p "$dir")"
  echo "  增量编译 #3: ${i3}s" >&2
  jar="$(ls "$dir"/build/libs/*.jar 2>/dev/null | head -1 || true)"
  if [ -n "$jar" ]; then
    size="$(jar_size "$jar")"
    echo "  jar: $jar（${size} B）" >&2
  else
    size="N/A"
    echo "  警告: 未找到 $dir/build/libs/*.jar" >&2
  fi
  printf '| %s | 冷编译（build --clean） | %s | %s | %s | %s | %s |\n' \
    "$name" "$c1" "$c2" "$c3" "$(median "$c1" "$c2" "$c3")" "$size"
  printf '| %s | 增量编译（build，缓存命中） | %s | %s | %s | %s | %s |\n' \
    "$name" "$i1" "$i2" "$i3" "$(median "$i1" "$i2" "$i3")" "$size"
}

# ---- 纯编译样例：yuxc run samples/hello.yux ×3 ----
bench_hello() {
  local file="samples/hello.yux" r1 r2 r3
  echo "== 纯编译样例: yuxc run $file ×3 ==" >&2
  r1="$(time_seconds "$YUXC" run "$file")"
  echo "  run #1: ${r1}s" >&2
  r2="$(time_seconds "$YUXC" run "$file")"
  echo "  run #2: ${r2}s" >&2
  r3="$(time_seconds "$YUXC" run "$file")"
  echo "  run #3: ${r3}s" >&2
  printf '| %s | run（纯编译 + 运行） | %s | %s | %s | %s | N/A（无 jar） |\n' \
    "$file" "$r1" "$r2" "$r3" "$(median "$r1" "$r2" "$r3")"
}

# ---- 执行基准 ----
echo "== 环境 ==" >&2
echo "  日期: $BENCH_DATE" >&2
echo "  git: $GIT_REV（$GIT_BRANCH）" >&2
echo "  JDK: $JDK_VERSION（JAVA_HOME=$JAVA_HOME）" >&2
echo "  Gradle: $GRADLE_VERSION" >&2

BODY=""
BODY+="$(bench_project helloworld samples/helloworld)
"
BODY+="$(bench_project mixed samples/mixed)
"
BODY+="$(bench_hello)
"

# ---- 汇总 Markdown 报告 ----
MD="$(cat <<EOF
# Yux 编译基准（M11 T-M11-5）

M11 里程碑编译耗时 / 输出 jar 体积基线。复跑：\`./bench/run-bench.sh --out bench/RESULTS.md\`（先跑一次 warm-up 再复测）。波动说明：并发机器上冷编译受 Gradle daemon 与系统负载影响较大，单次测量可偏离中位数 30%+；增量编译反映缓存命中时的真实成本，相对稳定。

- 日期: $BENCH_DATE
- git: \`$GIT_REV\`（$GIT_BRANCH）
- JDK: $JDK_VERSION（JAVA_HOME=$JAVA_HOME）
- Gradle: $GRADLE_VERSION
- 计时: /usr/bin/time -f %e（缺失时 date +%s.%N / \$SECONDS）

## 结果

| 工程 | 命令 | 第1次(s) | 第2次(s) | 第3次(s) | 中位数(s) | 体积(B) |
|---|---|---|---|---|---|---|
$BODY
注：体积为 \`build/libs/*.jar\` 产物字节数；hello.yux 为纯编译样例，无 jar 产物。
EOF
)"

printf '%s\n' "$MD"
if [ -n "$OUT_FILE" ]; then
  printf '%s\n' "$MD" > "$OUT_FILE"
  echo "== 报告已写入: $OUT_FILE" >&2
fi
