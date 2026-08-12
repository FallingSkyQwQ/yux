#!/usr/bin/env bash
#
# setupyux.sh — 一键安装 yux 工具链（yuxc）。
#
# 用法：
#   curl -fsSL https://raw.githubusercontent.com/FallingSkyQwQ/yux/main/setupyux.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/FallingSkyQwQ/yux/main/setupyux.sh | bash -s -- --ref v0.1.0-m15
#
# 平台：Linux / macOS / WSL。需要 JDK 21+（缺失时脚本输出安装指引，不自动安装）。
#
# 安装策略（按优先级）：
#   1. 目标版本已有发布产物（GitHub Releases 的 yuxc-<ver>.tar）→ 直接下载解压，免编译；
#   2. 否则 clone 仓库到临时目录，用 Gradle installDist 源码编译安装。
#
# 安装位置：~/.yux/bin/yuxc，并（幂等地）将 ~/.yux/bin 加入 PATH（写入 ~/.bashrc）。
#
set -euo pipefail

REPO="FallingSkyQwQ/yux"
BASE_URL="https://github.com/${REPO}"
RAW_BASE="https://raw.githubusercontent.com/${REPO}"
PREFIX="${YUX_PREFIX:-$HOME/.yux}"
BIN_DIR="$PREFIX/bin"
REF="${YUX_REF:-}"            # 空 = main 最新；可指定 tag（v0.1.0-m15）或 commit
KEEP_SRC="${YUX_KEEP_SRC:-0}" # 1 = 编译后不删除临时源码目录（调试用）
QUIET="${YUX_QUIET:-0}"
FORCE_DIST="${YUX_FORCE_DIST:-0}" # 1 = 跳过发布产物，强制源码编译

say() { if [ "$QUIET" != "1" ]; then printf '%s\n' "$*"; fi; }
die() {
    printf 'setupyux: 错误: %s\n' "$*" >&2
    exit 1
}

# --- 帮助 ---
if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    cat <<'EOF'
setupyux — 一键安装 yux 工具链

用法:
  bash setupyux.sh [选项]

选项:
  --ref <tag|commit>   锁定版本（默认 main 最新）。例: --ref v0.1.0-m15
  --force-dist         跳过发布产物，强制源码编译
  --keep-src           编译后保留临时源码目录（调试用）
  --quiet              静默输出
  --prefix <dir>       安装目录（默认 ~/.yux）

环境变量等价: YUX_REF / YUX_FORCE_DIST / YUX_KEEP_SRC / YUX_QUIET / YUX_PREFIX
EOF
    exit 0
fi

# --- 参数解析 ---
while [ $# -gt 0 ]; do
    case "$1" in
    --ref)
        REF="${2:?--ref 需要一个值}"
        shift 2
        ;;
    --force-dist)
        FORCE_DIST=1
        shift
        ;;
    --keep-src)
        KEEP_SRC=1
        shift
        ;;
    --quiet)
        QUIET=1
        shift
        ;;
    --prefix)
        PREFIX="${2:?--prefix 需要一个值}"
        BIN_DIR="$PREFIX/bin"
        shift 2
        ;;
    --)
        shift
        break
        ;;
    *) die "未知参数: $1（--help 查看用法）" ;;
    esac
done

# --- 平台检查 ---
case "$(uname -s)" in
Linux | Darwin) ;;
*) die "暂不支持 $(uname -s)；Linux / macOS / WSL 用户请用 WSL 后再试。" ;;
esac

# --- JDK 检查（≥21）---
JAVA_BIN=""
if command -v java >/dev/null 2>&1; then
    ver="$(java -version 2>&1 | head -n 1)"
    if echo "$ver" | grep -qE '"2[1-9]|"([3-9][0-9])'; then
        JAVA_BIN="$(command -v java)"
    fi
fi
if [ -z "$JAVA_BIN" ]; then
    cat >&2 <<EOF
setupyux: 错误: 需要 JDK 21+（未检测到可用 java）。

安装指引：
  - Ubuntu/Debian:  sudo apt install openjdk-21-jdk
  - macOS (brew):   brew install openjdk@21
  - 任意平台:       https://adoptium.net/ 下载 Temurin 21，并配置 PATH。

检测通过后重新运行本脚本即可。
EOF
    exit 1
fi
say "已检测到 JDK: $($JAVA_BIN -version 2>&1 | head -n 1)"

mkdir -p "$BIN_DIR"

# --- 解析目标版本 ---
if [ -n "$REF" ]; then
    VER="$REF"
    if [ "${VER#v}" = "$VER" ]; then
        # 不带 v 前缀的 commit SHA 保持原样；看起来像语义版本则补 v
        if echo "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+' && echo "$VER" | grep -qE '^v'; then
            :
        elif echo "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+'; then
            VER="v$VER"
        fi
    fi
else
    VER="latest"
fi

install_from_release() {
    # 尝试 GitHub Releases 的 tar 产物：yuxc-<ver>.tar
    local tag="$1" url tarfile
    url="${BASE_URL}/releases/download/${tag}/yuxc-${tag}.tar"
    tarfile="$(mktemp)"
    say "尝试下载发布产物: ${tag}"
    if curl -fsSL -o "$tarfile" "$url" 2>/dev/null; then
        local tmp
        tmp="$(mktemp -d)"
        tar -xf "$tarfile" -C "$tmp"
        if [ -x "$tmp/yuxc/bin/yuxc" ]; then
            cp -R "$tmp/yuxc/." "$PREFIX/"
            rm -rf "$tmp"
            rm -f "$tarfile"
            return 0
        fi
        rm -rf "$tmp"
    fi
    rm -f "$tarfile"
    return 1
}

if [ "$FORCE_DIST" = "1" ]; then
    say "已指定 --force-dist，跳过发布产物，走源码编译。"
else
    # 只有显式 tag 才尝试发布产物；latest/main 一律源码编译
    if [ "$VER" != "latest" ] && install_from_release "$VER"; then
        say "已从发布产物安装 yux ${VER} → ${BIN_DIR}"
        "$BIN_DIR/yuxc" --help >/dev/null 2>&1 || true
        say "完成。运行 'yuxc run hello.yux' 开始使用；或先执行下面的 PATH 配置。"
        exit 0
    fi
    if [ "$VER" != "latest" ]; then
        say "无 ${VER} 的发布产物，回退到源码编译。"
    fi
fi

# --- 源码编译路径 ---
WORK="$(mktemp -d)"
cleanup() { if [ "$KEEP_SRC" != "1" ]; then rm -rf "$WORK"; else say "保留源码目录: $WORK"; fi; }
trap cleanup EXIT

say "克隆 ${REPO}（${VER}）→ 临时目录编译..."
git clone --quiet --filter=blob:none "${BASE_URL}.git" "$WORK/yux"
if [ "$VER" != "latest" ]; then
    (cd "$WORK/yux" && git -c advice.detachedHead=false checkout --quiet "$REF")
fi

say "构建 yuxc（./gradlew :yux-compiler:yux-compiler-cli:installDist）..."
(cd "$WORK/yux" && ./gradlew --quiet :yux-compiler:yux-compiler-cli:installDist)

DIST_BIN="$WORK/yux/yux-compiler/yux-compiler-cli/build/install/yuxc/bin/yuxc"
[ -x "$DIST_BIN" ] || die "构建成功但未找到 yuxc: $DIST_BIN"

cp -R "$WORK/yux/yux-compiler/yux-compiler-cli/build/install/yuxc/." "$PREFIX/"
chmod +x "$BIN_DIR/yuxc"

# --- PATH 配置（幂等）---
install_path() {
    case ":$PATH:" in
    *":$BIN_DIR:"*) return 0 ;;
    esac
    local rc="$HOME/.bashrc"
    if [ -f "$HOME/.zshrc" ]; then rc="$HOME/.zshrc"; fi
    if ! grep -qF "export PATH=\"$BIN_DIR" "$rc" 2>/dev/null; then
        printf '\n# yux 工具链\nexport PATH="%s:$PATH"\n' "$BIN_DIR" >>"$rc"
    fi
}
install_path
export PATH="$BIN_DIR:$PATH"

say "已安装 yux ${VER} → ${BIN_DIR}"
say "PATH 已写入 $([ -f "$HOME/.zshrc" ] && echo ~/.zshrc || echo ~/.bashrc)（新终端生效；当前终端可执行: export PATH=\"$BIN_DIR:\$PATH\"）"
"$BIN_DIR/yuxc" --help >/dev/null 2>&1 || true
say "完成。运行 'yuxc run hello.yux' 开始使用。"
