#!/usr/bin/env bash
#
# firestar-service 停止脚本（兼容 Linux 生产环境 / Windows(Git Bash) 本地开发）
# 位置约定: bin/stop.sh
# 用法:
#   bash bin/stop.sh
#
set -uo pipefail

# ---- 定位项目根目录 (bin/ 的上一级) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$APP_HOME"

APP_NAME="firestar-server"
PORT="${DEPLOY_PORT:-2025}"
PID_FILE="$APP_HOME/logs/app.pid"

# ---- OS 探测 ----
OS_RAW="$(uname -s 2>/dev/null || echo unknown)"
case "$OS_RAW" in
  MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
  *)                     OS="linux" ;;
esac
[ "$OS" = "windows" ] && export MSYS_NO_PATHCONV=1

# ---- 取监听指定端口的进程 PID（跨平台）----
port_pid() {
  local port="$1"
  if [ "$OS" = "windows" ]; then
    netstat -ano 2>/dev/null | awk -v p=":$port " '$0 ~ p && /LISTENING/ {print $5; exit}'
  else
    ss -ltnp 2>/dev/null | grep -E "[:.]${port}([[:space:]]|$)" | grep -oE 'pid=[0-9]+' | head -1 | sed 's/pid=//'
  fi
}

# ---- 收集目标 PID：PID 文件 + jar 名(仅 Linux) + 端口(兜底) ----
PIDS=()
[ -f "$PID_FILE" ] && PIDS+=("$(cat "$PID_FILE" 2>/dev/null || true)")
if [ "$OS" = "linux" ]; then
  PIDS+=($(pgrep -f "firestar-server.*\.jar" 2>/dev/null || true))
fi
PP="$(port_pid "$PORT")"
[ -n "$PP" ] && PIDS+=("$PP")
PIDS=($(printf "%s\n" "${PIDS[@]}" | grep -v '^$' | sort -u))

if [ ${#PIDS[@]} -eq 0 ]; then
  echo "[stop] 未发现运行中的 $APP_NAME 进程 (port=$PORT)"
  rm -f "$PID_FILE"
  exit 0
fi

echo "[stop] 准备停止 PID: ${PIDS[*]} (os=$OS)"

# ---- 1) 优雅终止 ----
for p in "${PIDS[@]}"; do
  if [ "$OS" = "windows" ]; then
    taskkill /PID "$p" 2>/dev/null || true
  else
    kill -TERM "$p" 2>/dev/null || true
  fi
done

# ---- 2) 等待优雅退出（最多 ~10s）----
for i in $(seq 1 10); do
  alive=0
  for p in "${PIDS[@]}"; do
    if [ "$OS" = "windows" ]; then
      tasklist /FI "PID eq $p" 2>/dev/null | grep -q "$p" && alive=1
    else
      kill -0 "$p" 2>/dev/null && alive=1
    fi
  done
  [ "$alive" -eq 0 ] && break
  sleep 1
done

# ---- 3) 强制终止 ----
for p in "${PIDS[@]}"; do
  if [ "$OS" = "windows" ]; then
    if tasklist /FI "PID eq $p" 2>/dev/null | grep -q "$p"; then
      echo "[stop] PID $p 优雅退出超时，强制终止"
      taskkill /F /PID "$p" 2>/dev/null || true
    fi
  else
    if kill -0 "$p" 2>/dev/null; then
      echo "[stop] PID $p 优雅退出超时，强制终止"
      kill -KILL "$p" 2>/dev/null || true
    fi
  fi
done

# ---- 4) 确认端口已释放 ----
freed=0
for i in $(seq 1 15); do
  if [ -n "$(port_pid "$PORT")" ]; then
    sleep 1
  else
    freed=1
    break
  fi
done
[ "$freed" -eq 0 ] && echo "[stop] 警告: 端口 $PORT 在超时内仍未释放，请手动检查" >&2

rm -f "$PID_FILE"
echo "[stop] $APP_NAME 已停止（端口 $PORT 已释放）"
