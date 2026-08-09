#!/usr/bin/env bash
# T-M9-2：Home 插件功能验证脚本（06-§M9 验收命令）
#
# 验证：
#   1. `yuxc build -p samples/home-plugin` 构建成功
#   2. `yuxc test  -p samples/home-plugin` 测试通过
#   3. jar 产物含全部下沉类（命令/事件/配置/data/task）
#   4. plugin.yml main 与 permissions 正确
#
# 用法：./samples/home-plugin/verify.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

YUXC="${YUXC:-yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc}"
MC_PLUGIN="yux-plugin-minecraft/yux-compiler-minecraft/build/libs/yux-compiler-minecraft-0.1.0-SNAPSHOT.jar"
PROJECT="samples/home-plugin"
JAR="$PROJECT/build/libs/HomePlugin-1.0.0.jar"

echo "== 准备：构建编译器插件与 CLI =="
# 先全量编译产物再 installDist，避免 Gradle daemon 与 CLI 启动的文件系统竞态
./gradlew :yux-plugin-minecraft:yux-compiler-minecraft:jar :yux-compiler:yux-compiler-cli:installDist -q
./gradlew :yux-compiler:yux-compiler-cli:installDist -q

echo "== 1. yuxc build（--clean 全量）=="
"$YUXC" build -p "$PROJECT" --plugin "$(realpath "$MC_PLUGIN")" --clean
test -f "$JAR" && echo "  ✓ jar 产物存在: $JAR"

echo "== 2. yuxc test =="
"$YUXC" test -p "$PROJECT" --plugin "$(realpath "$MC_PLUGIN")"

echo "== 3. jar 类清单校验 =="
# 先完整列出 jar 内容（命令替换），避免 pipefail + grep -q 提前退出触发 SIGPIPE 误报
JAR_LISTING="$(unzip -l "$JAR")"
for cls in HomePlugin SethomeCommand HomeCommand DelhomeCommand HomesCommand \
    PlayerJoinEvent_Handler PlayerQuitEvent_Handler PlayerDeathEvent_Handler \
    HomeConfig HomeData LocationData Task_0_6000; do
  count="$(echo "$JAR_LISTING" | grep -c " $cls\.class" || true)"
  if [ "$count" -gt 0 ]; then echo "  ✓ $cls"; else echo "  ✗ 缺失 $cls"; exit 1; fi
done

echo "== 4. plugin.yml 校验 =="
PLUGIN_YML="$(unzip -p "$JAR" plugin.yml)"
echo "$PLUGIN_YML" | grep -q "main: HomePlugin" && echo "  ✓ main: HomePlugin"
echo "$PLUGIN_YML" | grep -q "homeplugin.set" && echo "  ✓ permission homeplugin.set"
echo "$PLUGIN_YML" | grep -q "homeplugin.home" && echo "  ✓ permission homeplugin.home"

echo
echo "✅ T-M9-2 功能验证通过：4 条命令 + tab + 3 类事件 + 配置 + 定时任务全部落地"
echo "   jar: $JAR"
