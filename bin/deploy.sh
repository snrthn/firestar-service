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

# ---- 【2026-11-16 新增】启动前确保数据库存在（从 0 建库 / 库存在则跳过）----
# 为什么需要：Flyway 只负责在建好的库里建表，不负责建库；全新环境（新 ECS / 新实例 /
#   库被误删）里 DB_URL 指向的库不存在时，Spring 启动连库直接失败。这里用生产部署账号
#   做幂等建库，随后 Flyway 全量重放建表 + 种子。
# 幂等语义：CREATE DATABASE IF NOT EXISTS —— 库在则 no-op（实测 rc=0 零输出、表数不变），
#   不在则按 utf8mb4/unicode_ci 新建（与本地 reset_test_db.sql、现网建库口径一致）。
# 安全护栏（三条，任一不满足即 fail-fast，绝不带病启动）：
#   1) DB_URL 必须是 jdbc:mysql:// 且解析出库名；库名白名单且硬钉 = firestar。
#      —— 迁移脚本 V27/V28/V75 的幂等守卫写死 table_schema='firestar'，换库名会让守卫
#         与 DDL 作用域失配（P1/P2 血案），故此处不匹配就拒绝，而不是建一个错库。
#   2) 只做 CREATE，绝无 DROP/ALTER；用的是云效注入的 DB_USERNAME（firestar_user，
#      已实证对 firestar.* 有 Create_priv），不引入更高权限、不碰其它业务库。
#   3) 建库失败立即中止部署 —— 此时旧服务还没停，不会造成「服务停了库却没起」的半吊子状态。
# scheme 判定用「前缀」而非正则捕获：`jdbc:mysql` 中间含冒号，
# 用 [a-zA-Z]+ 捕获 scheme 会只匹配到 jdbc、后面接不上 :// 而失配。
case "$DB_URL" in
  jdbc:mysql://*) ;;
  *) echo "[deploy][FATAL] DB_URL 不是 jdbc:mysql:// 前缀，拒绝建库（DB_URL=${DB_URL%%\?*}）" >&2; exit 1 ;;
esac
DB_TARGET_NAME="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' | tr -d '\r')"
DB_TARGET_HOST="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://([^:/]+).*#\1#' | tr -d '\r')"
DB_TARGET_PORT="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://[^:/]+:([0-9]+).*#\1#' | tr -d '\r')"
case "$DB_TARGET_PORT" in ''|*[!0-9]*) DB_TARGET_PORT=3306 ;; esac   # DB_URL 未写端口则回落 3306
if [[ ! "$DB_TARGET_NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "[deploy][FATAL] 从 DB_URL 解析的库名非法: '$DB_TARGET_NAME'，拒绝建库" >&2
  exit 1
fi
if [ "$DB_TARGET_NAME" != "firestar" ]; then
  echo "[deploy][FATAL] DB_URL 库名='$DB_TARGET_NAME' != 预期 'firestar'；迁移脚本守卫写死 firestar，拒绝建错库" >&2
  exit 1
fi
MYSQL_CLI="/usr/local/mysql80/bin/mysql"
[ -x "$MYSQL_CLI" ] || MYSQL_CLI="$(command -v mysql 2>/dev/null || true)"
if [ -z "$MYSQL_CLI" ]; then
  echo "[deploy][FATAL] 找不到 mysql CLI，无法确保数据库存在" >&2
  exit 1
fi
echo "[deploy] 确保数据库存在: ${DB_TARGET_NAME} @ ${DB_TARGET_HOST}:${DB_TARGET_PORT} (CREATE IF NOT EXISTS)"
if ! MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLI" \
      -h "$DB_TARGET_HOST" -P "$DB_TARGET_PORT" -u "$DB_USERNAME" \
      --default-character-set=utf8mb4 -N -B \
      -e "CREATE DATABASE IF NOT EXISTS ${DB_TARGET_NAME} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" \
      >/dev/null; then
  echo "[deploy][FATAL] 建库失败（库不存在且无 CREATE 权限 / 实例不可达）；旧服务未停，请排查后重试" >&2
  exit 1
fi
echo "[deploy] DB_READY=${DB_TARGET_NAME}（已存在则跳过，不存在则新建）"

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
        # 仅镜像合法标识符 key；云效/CI agent 可能注入名字非法的脏变量（如 }=}），
        # 若写进 prod.env 会被 start.sh 的 load_env 以非法标识符 export 而崩溃
        # （部署 run#128 失败根因）。此处从源头过滤，避免脏数据持久化。
        case "$k" in
          *[!A-Za-z0-9_]*) printf '[deploy] 跳过非法 key: %s\n' "$k" >&2; continue ;;
          [0-9]*)          printf '[deploy] 跳过数字开头 key: %s\n' "$k" >&2; continue ;;
          "") continue ;;
          *) printf '%s=%s\n' "$k" "$v" ;;                   # 值不加引号，保持与 .env 一致
        esac
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
