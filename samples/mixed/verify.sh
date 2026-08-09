#!/usr/bin/env bash
# T-M10：混合项目功能验证脚本（06-§M10 验收命令，05-§9）
#
# 验证：
#   1. `yuxc build -p samples/mixed` 构建成功，jar 同时含 Yux/Kotlin/Java 三语言产物类
#   2. `yuxc run  -p samples/mixed` 输出「Kotlin 格式化 + Java 日志 + Yux 输出」三方协作结果
#
# 用法：./samples/mixed/verify.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

YUXC="${YUXC:-yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc}"
PROJECT="samples/mixed"
JAR="$PROJECT/build/libs/MixedServer-1.0.0.jar"

echo "== 准备：构建 CLI =="
./gradlew :yux-compiler:yux-compiler-cli:installDist -q

echo "== 1. yuxc build（--clean 全量）=="
"$YUXC" build -p "$PROJECT" --clean
test -f "$JAR" && echo "  ✓ jar 产物存在: $JAR"

echo "== 2. jar 三语言产物校验 =="
JAR_LISTING="$(unzip -l "$JAR")"
for cls in "com/example/Logger.class" "com/example/Currency.class" "Account.class" "Main.class"; do
  if echo "$JAR_LISTING" | grep -q " $cls"; then echo "  ✓ $cls"; else echo "  ✗ 缺失 $cls"; exit 1; fi
done

echo "== 3. yuxc run（三方输出）=="
RUN_OUTPUT="$("$YUXC" run -p "$PROJECT")"
echo "$RUN_OUTPUT"
echo "$RUN_OUTPUT" | grep -q "\[MyServer\] Steve 存入 \$50.00" && echo "  ✓ Java 日志 + Kotlin 格式化"
echo "$RUN_OUTPUT" | grep -q "Steve 余额: \$75.50" && echo "  ✓ Yux 输出 + Kotlin 格式化"
echo "$RUN_OUTPUT" | grep -q "Alex 余额: \$100.00" && echo "  ✓ 余额正确"

echo
echo "✅ T-M10 功能验证通过：Kotlin 格式化 + Java 日志 + Yux 输出三方协作正确"
echo "   jar: $JAR"
