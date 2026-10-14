#!/usr/bin/env bash
#
# firestar-service 生产部署脚本（适配云效「执行命令」步骤）
# 部署目录约定: /usr/local/server/java/firestar
#   云效「上传文件」保持相对结构 -> target/firestar-service-*.jar + bin/deploy.sh
# 凭据注入: DB_URL / DB_USERNAME / DB_PASSWORD 由云效 Secrets 以环境变量注入（不落盘、不写库）
# 云效执行命令: bash /usr/local/server/java/firestar/bin/deploy.sh
#
set -euo pipefail

# ---- 定位部署目录 (bin/ 的上一级) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$APP_HOME"

# ---- 凭据来源：仅依赖云效流水线「主机部署」步骤注入的环境变量（私密变量，不落盘）----
PORT="${DEPLOY_PORT:-2025}"
PROFILE="${SPRING_PROFILE:-prod}"

# ---- Java 运行时：制品按 Java 17 编译，必须用 Java 17 运行 ----
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
  for d in /usr/local/jdk/jdk-17.0.8 /usr/local/jdk/jdk1.8.0_371; do
    [ -x "$d/bin/java" ] && JAVA_HOME="$d" && break
  done
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
LOGS_DIR="$APP_HOME/logs"
PID_FILE="$LOGS_DIR/app.pid"
LOG_FILE="$LOGS_DIR/app.log"

# ---- 校验生产 DB 环境变量（由云效 Secrets 注入，缺失即终止）----
: "${DB_URL:?缺少 DB_URL 环境变量(请在云效流水线配置 Secrets)}"
: "${DB_USERNAME:?缺少 DB_USERNAME 环境变量(请在云效流水线配置 Secrets)}"
: "${DB_PASSWORD:?缺少 DB_PASSWORD 环境变量(请在云效流水线配置 Secrets)}"

# ---- 停旧实例：复用 stop.sh ----
bash "$SCRIPT_DIR/stop.sh"

# ---- 启动：复用 start.sh（setsid 守护化 + liveness 探针门禁）----
# 探针走 /actuator/health/liveness：只验进程存活+磁盘，不依赖 DB / 数据自检，
# 避免「生产库种子数与基线不符 / 库慢」被误判成启动超时。STARTUP_TIMEOUT 默认 180s。
export SPRING_PROFILE="$PROFILE"
bash "$SCRIPT_DIR/start.sh" "$PORT" "/actuator/health/liveness"
exit $?
