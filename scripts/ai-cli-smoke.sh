#!/usr/bin/env bash
# HMCL-AE AI agent behavioural smoke suite — PARALLEL runner.
#
# Drives the headless CLI (AiCli) with REALISTIC, vague, noob-style prompts — the way an actual
# 抖音小白 would type, NOT clean technical phrasing — and checks that the agent reaches the right
# native tool (and never falls back to shell for things it shouldn't).
#
# WHY NOT `./gradlew :HMCL:runAiCli` PER SCENARIO?
#   runAiCli is a JavaExec that holds the Gradle project lock for the whole run, so spawning
#   several `gradlew` processes is effectively SERIAL — they queue on the lock. To get real
#   parallelism we bypass Gradle: a one-shot `:HMCL:printAiCliRun` exports the exact runtime
#   classpath + JVM args (the same ones runAiCli uses), then we launch many plain `java -cp ...`
#   AiCli processes concurrently.
#
# Baseline model: mercury-2 (low IQ, fast) from .ai-cli-test.json — if the dumbest model routes
# correctly, the prompt/tool design is robust. Add --fallback to switch to the fallback provider.
#
# Usage:  bash scripts/ai-cli-smoke.sh [--fallback] [--only <substr>] [--jobs N]
#   --only <substr>  run only scenarios whose name contains <substr>
#   --fallback       use the config's fallback provider
#   --jobs N         max concurrent JVMs (default 4) — keeps 16 JVMs from crushing the machine
# Each scenario has a hard --timeout AND an outer `timeout` wrapper so the suite NEVER hangs.

set -u
cd "$(dirname "$0")/.."
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}" HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7890}"

FALLBACK=""; ONLY=""; JOBS=4
while [ $# -gt 0 ]; do
  case "$1" in
    --fallback) FALLBACK="--fallback";;
    --only) ONLY="$2"; shift;;
    --jobs) JOBS="$2"; shift;;
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

# ---- 1. One-shot Gradle export: classpath + JVM args + java exe + main class. ----
# Also compileTestJava so the testRuntime classpath's JavaFX jars are resolved/built. This is
# the ONLY gradle invocation — everything after runs as bare `java` for true parallelism.
echo "──────── preparing (gradle export) ────────"
if ! ./gradlew :HMCL:printAiCliRun :HMCL:compileTestJava -q; then
  echo "✗ gradle printAiCliRun/compileTestJava failed — aborting." >&2
  exit 1
fi

CPF=HMCL/build/aicli-classpath.txt
ARGF=HMCL/build/aicli-jvmargs.txt
JAVAF=HMCL/build/aicli-java.txt
MAINF=HMCL/build/aicli-main.txt
for f in "$CPF" "$ARGF" "$JAVAF" "$MAINF"; do
  [ -s "$f" ] || { echo "✗ missing/empty export file: $f" >&2; exit 1; }
done
CP="$(cat "$CPF")"
JVMARGS="$(cat "$ARGF")"      # space-separated, no arg contains spaces → safe to word-split
JAVA="$(cat "$JAVAF")"
MAIN="$(cat "$MAINF")"
echo "  java: $JAVA"
echo "  jobs: $JOBS   timeout/scenario: ${TIMEOUT}s"

# ---- 2. Build the filtered scenario list into parallel arrays. ----
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
declare -a S_NAME S_EXPECT S_ANS S_PROMPT S_OUT
N=0
for s in "${SCENARIOS[@]}"; do
  IFS='|' read -r name expect answers prompt extra <<< "$s"
  name="$(echo "$name" | xargs)"; expect="$(echo "$expect" | xargs)"
  answers="$(echo "$answers" | xargs)"; prompt="$(echo "$prompt" | xargs)"
  [ -n "$ONLY" ] && [[ "$name" != *"$ONLY"* ]] && continue
  S_NAME[$N]="$name"; S_EXPECT[$N]="$expect"; S_ANS[$N]="$answers"
  S_PROMPT[$N]="$prompt"; S_OUT[$N]="$WORKDIR/$N.out"
  N=$((N+1))
done
if [ "$N" -eq 0 ]; then echo "No scenarios matched --only '$ONLY'."; exit 0; fi

# ---- 3. Launch each scenario as a bare `java` process, concurrency capped at $JOBS. ----
# Outer `timeout` (a bit above the in-process --timeout) is a second anti-hang net so a wedged
# JVM can't block `wait`. Each process streams to its own file; we judge after all finish.
run_job() {
  local i="$1"
  local prompt="${S_PROMPT[$i]}" answers="${S_ANS[$i]}" out="${S_OUT[$i]}"
  local cmd=( timeout $((TIMEOUT+30)) "$JAVA" )
  cmd+=( $JVMARGS )                         # intentional word-split (args have no spaces)
  cmd+=( -cp "$CP" "$MAIN" --prompt "$prompt" --timeout "$TIMEOUT" )
  [ -n "$FALLBACK" ] && cmd+=( --fallback )
  local a
  for a in $answers; do cmd+=( --answer "$a" ); done
  "${cmd[@]}" > "$out" 2>&1
}

echo "──────── launching $N scenario(s), up to $JOBS in parallel ────────"
START=$(date +%s)
running=0
for ((i=0; i<N; i++)); do
  echo "  ▶ start [${S_NAME[$i]}]"
  run_job "$i" &
  running=$((running+1))
  if [ "$running" -ge "$JOBS" ]; then
    wait -n 2>/dev/null || wait    # bash<4.3 lacks `wait -n` → fall back to draining all
    running=$((running-1))
  fi
done
wait
END=$(date +%s)
echo "──────── all finished in $((END-START))s ────────"
echo

# ---- 4. Judge each scenario (same rules as before) and print in original style/order. ----
for ((i=0; i<N; i++)); do
  name="${S_NAME[$i]}"; expect="${S_EXPECT[$i]}"; prompt="${S_PROMPT[$i]}"; out="${S_OUT[$i]}"
  echo "──────── [$name] ────────"
  echo "  prompt: $prompt"
  tools="$(grep -oE '\[TOOL→\] [a-z_]+' "$out" | sed 's/\[TOOL→\] /  →/' | sort -u | tr '\n' ' ')"
  result="$(grep -oE '\[RESULT\][^\n]*' "$out" | head -1)"
  echo "  tools:${tools:-  (none)}"
  echo "  $result"
  ok=1
  grep -q "\[RESULT\]" "$out" || { ok=0; echo "  ✗ no RESULT (hang/crash?)"; }
  # A turn that TIMED OUT or FAILED is not a pass, even if it called the right tool.
  if grep -qE '\[RESULT\] (TIMEOUT|FAILED)' "$out"; then ok=0; echo "  ✗ RESULT was TIMEOUT/FAILED"; fi
  if grep -qE '\[TOOL→\] shell' "$out"; then ok=0; echo "  ✗ used shell (should not)"; fi
  if [ "$expect" != "-" ]; then
    grep -q "\[TOOL→\] $expect" "$out" || { ok=0; echo "  ✗ expected tool '$expect' not called"; }
  fi
  if [ $ok -eq 1 ]; then PASS=$((PASS+1)); echo "  ✓ PASS"; else FAIL=$((FAIL+1)); fi
  echo
done

echo "════════ SMOKE SUMMARY: PASS=$PASS FAIL=$FAIL ════════"
[ $FAIL -eq 0 ]
