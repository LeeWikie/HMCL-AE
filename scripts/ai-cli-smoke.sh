#!/usr/bin/env bash
# HMCL-AE AI agent behavioural smoke suite.
#
# Drives the headless CLI (`:HMCL:runAiCli`) with REALISTIC, vague, noob-style prompts —
# the way an actual 抖音小白 would type, NOT clean technical phrasing — and checks that the
# agent reaches the right native tool (and never falls back to shell for things it shouldn't).
#
# Baseline model: mercury-2 (low IQ, fast) from .ai-cli-test.json — if the dumbest model
# routes correctly, the prompt/tool design is robust. Add --fallback to switch to DeepSeek.
#
# Usage:  bash scripts/ai-cli-smoke.sh [--fallback] [--only <substr>]
# Each scenario has a hard --timeout so the suite NEVER hangs.

set -u
cd "$(dirname "$0")/.."
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}" HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7890}"

FALLBACK=""; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --fallback) FALLBACK="--fallback";;
    --only) ONLY="$2"; shift;;
  esac; shift
done

TIMEOUT=90
PASS=0; FAIL=0

# scenario := "name | expected_tool | --answer args | realistic noob prompt"
# expected_tool: a tool name we expect to see in a [TOOL→] line. "-" = just expect RESULT OK.
SCENARIOS=(
  "实例列表 | list_instances | | 我都装了些啥版本啊"
  "最新版本 | list_game_versions | | 现在最新的我的世界是几"
  "电脑配置 | system_info | | 我这破电脑能跑得动不"
  "Java环境 | list_java | | java咋回事 要不要装"
  "找性能mod | - | | 游戏好卡 有没有啥能让它流畅点的"
  "找光影 | search_shaders | | 想让游戏变好看 整个光影呗"
  "已装mod | list_mods | | 我之前装了哪些mod来着"
  "存档列表 | list_worlds | | 我那个世界还在吗"
  "服务器列表 | list_servers | | 我加过哪些服务器"
  "实例详情 | instance_details | | 我这个实例用的啥加载器 版本是多少"
  "资源包 | list_resourcepacks | | 我有哪些材质包"
  "截图 | list_screenshots | | 我截的图在哪"
  "记忆-存 | remember | | 记一下 我以后都用fabric"
  "装整合包(模糊→ask) | search_modpack | | 给我整个好玩的整合包 | 0"
  "登录正版 | microsoft_login | | 我想用正版账号登录"
  "诊断崩溃 | - | | 我游戏崩了 进不去 咋办"
)

run_one() {
  local name="$1" expect="$2" answers="$3" prompt="$4"
  local args="--prompt '$prompt' --timeout $TIMEOUT $FALLBACK"
  for a in $answers; do args="$args --answer $a"; done
  echo "──────── [$name] ────────"
  echo "  prompt: $prompt"
  local out
  out="$(eval timeout $((TIMEOUT+40)) ./gradlew :HMCL:runAiCli --args=\"$args\" -q 2>&1)"
  local tools result
  tools="$(printf '%s\n' "$out" | grep -oE '\[TOOL→\] [a-z_]+' | sed 's/\[TOOL→\] /  →/' | sort -u | tr '\n' ' ')"
  result="$(printf '%s\n' "$out" | grep -oE '\[RESULT\][^\n]*' | head -1)"
  echo "  tools:${tools:-  (none)}"
  echo "  $result"
  local ok=1
  printf '%s\n' "$out" | grep -q "\[RESULT\]" || { ok=0; echo "  ✗ no RESULT (hang/crash?)"; }
  # A turn that TIMED OUT or FAILED is not a pass, even if it called the right tool.
  if printf '%s\n' "$out" | grep -qE '\[RESULT\] (TIMEOUT|FAILED)'; then ok=0; echo "  ✗ RESULT was TIMEOUT/FAILED"; fi
  if printf '%s\n' "$out" | grep -qE '\[TOOL→\] shell'; then ok=0; echo "  ✗ used shell (should not)"; fi
  if [ "$expect" != "-" ]; then
    printf '%s\n' "$out" | grep -q "\[TOOL→\] $expect" || { ok=0; echo "  ✗ expected tool '$expect' not called"; }
  fi
  if [ $ok -eq 1 ]; then PASS=$((PASS+1)); echo "  ✓ PASS"; else FAIL=$((FAIL+1)); fi
  echo
}

for s in "${SCENARIOS[@]}"; do
  IFS='|' read -r name expect answers prompt extra <<< "$s"
  name="$(echo "$name" | xargs)"; expect="$(echo "$expect" | xargs)"
  answers="$(echo "$answers" | xargs)"; prompt="$(echo "$prompt" | xargs)"
  [ -n "$ONLY" ] && [[ "$name" != *"$ONLY"* ]] && continue
  run_one "$name" "$expect" "$answers" "$prompt"
done

echo "════════ SMOKE SUMMARY: PASS=$PASS FAIL=$FAIL ════════"
[ $FAIL -eq 0 ]
