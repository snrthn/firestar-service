#!/usr/bin/env bash
#
# firestar-service 生产部署脚本（适配云效「执行命令」步骤）
# 部署目录约定: /usr/local/server/java/firestar
#   云效「上传文件」保持相对结构 -> target/firestar-server-*.jar + bin/deploy.sh
# 凭据注入: DB_URL / DB_USERNAME / DB_PASSWORD 等运行时变量由云效 Secrets 以环境变量注入，
#           并在部署时全量镜像到 .env/prod.env 持久化（供重启 / systemd / 宿主重启复用，不再依赖云效瞬时注入）
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

# ---- 校验其它强制生产凭据（均由云效 Secrets 注入，缺失即终止，避免启动时静默失败）----
# 这些与 application-{prod}.properties 的 ${...} 占位符一一对应；不配置云效 Secrets 就会像本地
# dev 那样拿到字面量占位符、Flyway/AI/OSS 启动即崩。逐项 fail-fast，给出明确缺失项。
: "${JWT_SECRET:?缺少 JWT_SECRET 环境变量(请在云效 Secrets 配置，≥32 字节)}"
: "${AI_KEK:?缺少 AI_KEK 环境变量(请在云效 Secrets 配置 SM4 KEK，缺失则 AI 模块启动失败)}"
: "${COZE_PLUGIN_SECRET:?缺少 COZE_PLUGIN_SECRET 环境变量(请在云效 Secrets 配置)}"
: "${OSS_ACCESS_KEY_ID:?缺少 OSS_ACCESS_KEY_ID 环境变量(请在云效 Secrets 配置，事项2：OSS 密钥须迁云效)}"
: "${OSS_ACCESS_KEY_SECRET:?缺少 OSS_ACCESS_KEY_SECRET 环境变量(请在云效 Secrets 配置，事项2：OSS 密钥须迁云效)}"
: "${OSS_BUCKET:?缺少 OSS_BUCKET 环境变量(请在云效 Secrets 配置，prod=fire-star-prod)}"
: "${OSS_ENDPOINT:?缺少 OSS_ENDPOINT 环境变量(请在云效 Secrets 配置)}"

# ---- 全量镜像云效注入的运行时变量到 ECS，供重启 / systemd / 宿主重启复用 ----
# 云效注入是「任务级瞬时」的：任务一结束即消失，不写 /etc/environment、不留会话。
# 故每次部署把所有「非系统」环境变量镜像进 .env/prod.env，使其成为唯一运行时真相源：
#   手动重启 / 运维台重启 / systemd 宿主重启 —— 全部只认这份文件，不再依赖云效瞬时注入。
# 云效中新增或修改任何运行时变量，下次部署自动同步；手工改此文件会被下一次部署覆盖。
ENV_FILE="$APP_HOME/.env/prod.env"
mkdir -p "$(dirname "$ENV_FILE")"
TMP_ENV="$(mktemp)"
{
  echo "# 本文件由 deploy.sh 从云效环境变量全量生成（$(date '+%Y-%m-%d %H:%M:%S')），请勿手工编辑"
  echo "# 云效中新增/修改运行时变量 → 下次部署自动同步；手工改动会在下次部署被覆盖"
  # 排除 shell 自带系统变量，其余（云效注入的运行时配置）全部镜像
  # 值保持 KEY=VALUE 无引号，与 .env 各环境文件、云效 Secrets 写法完全一致；
  # 含 & 的 DB_URL 等由 start.sh 的 load_env 安全解析，无需在此转义或加引号。
  env | grep -vE '^(PATH|HOME|PWD|OLDPWD|USER|LOGNAME|SHELL|HOSTNAME|TERM|LANG|LC_[A-Z_]+|JAVA_HOME|MAVEN_HOME|MAVEN_OPTS|CLASSPATH|_|SHLVL|PS1|PIPELINE|BUILD_|GIT_|WORKSPACE|NODE_|HUDSON|JENKINS|CI_|DEPLOY_PORT|SPRING_PROFILE)=' | sort | while IFS= read -r line; do
    case "$line" in
      ''|\#*) printf '%s\n' "$line" ;;                      # 空行/注释原样透传
      *)
        k="${line%%=*}"; v="${line#*=}"
        printf '%s=%s\n' "$k" "$v"                          # 值不加引号，保持与 .env 一致
        ;;
    esac
  done
} > "$TMP_ENV"
chmod 600 "$TMP_ENV"
mv -f "$TMP_ENV" "$ENV_FILE"

# ---- 停旧实例：复用 stop.sh ----
bash "$SCRIPT_DIR/stop.sh"

# ---- 启动：复用 start.sh（setsid 守护化 + liveness 探针门禁）----
# 探针走 /actuator/health/liveness：只验进程存活+磁盘，不依赖 DB / 数据自检，
# 避免「生产库种子数与基线不符 / 库慢」被误判成启动超时。STARTUP_TIMEOUT 默认 180s。
export SPRING_PROFILE="$PROFILE"
bash "$SCRIPT_DIR/start.sh" "$PORT" "/actuator/health/liveness"
exit $?
