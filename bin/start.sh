#!/usr/bin/env bash
#
# firestar-service 启动脚本（兼容 Linux 生产环境 / Windows(Git Bash) 本地开发）
# 位置约定: bin/start.sh  (Maven/Spring Boot 项目运维脚本统一放 bin/，根目录保持整洁)
# 用法:
#   bash bin/start.sh [port] [health-path]
#   SPRING_PROFILE=test bash bin/start.sh 9090                 # 指定端口 + 切环境
#   SPRING_PROFILE=prod bash bin/start.sh 2025                     # 生产：门禁用 liveness
#   SPRING_PROFILE=prod bash bin/start.sh 2025 /actuator/health/liveness
#
# 说明:
#   - Windows(Git Bash) 用 netstat 看端口占用、用 taskkill 停止；Linux 用 kill/PID 文件；
#   - 门禁探针默认 /actuator/health/liveness（匿名、零信息披露）；
#   - ⚠️ 别把门禁改成 /actuator/health：2026-10-19 安全收口后它需要认证，
#     匿名轮询拿到 401 会被判成「启动超时」。完整健康改由应用启动自检写日志，见下文。
#
set -euo pipefail

# ---- 定位项目根目录 (bin/ 的上一级) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$APP_HOME"

# ---- OS 探测 ----
OS_RAW="$(uname -s 2>/dev/null || echo unknown)"
case "$OS_RAW" in
  MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
  *)                     OS="linux" ;;
esac
[ "$OS" = "windows" ] && export MSYS_NO_PATHCONV=1

APP_NAME="firestar-service"
# ---- Java 运行时定位（本地多 JDK 择优 + 交互选择）----
# 制品按 Java 17 编译（class file version 61），必须用 Java 17 运行，否则 UnsupportedClassVersionError。
# 解析优先级：
#   ① 环境变量 JAVA_HOME 已设且可用 → 直接用；
#   ② 否则读取 .env/java.env 中固定的 JAVA_HOME（用户可手动 pin，不污染全局环境）；
#   ③ 否则扫描本机常见 JDK 安装目录；
#   命中多个 Java 17 时，若为交互终端则列出菜单让你选，默认选第一个 17（生产/CI 非 TTY 时自动选，不阻塞）。
# （deploy.sh 在 ECS 上走自己的 Linux 路径解析，本段仅增强本地开发体验；如需对齐可同步。）

# 取 JDK 主版本号: "17.0.1" -> 17 ; "1.8.0_301" -> 8
java_major() {
  local out ver num first
  out="$("$1/bin/java" -version 2>&1)"
  ver="$(printf '%s\n' "$out" | head -1)"
  num="$(printf '%s' "$ver" | grep -oE '[0-9]+(\.[0-9]+)*' | head -1)"
  [ -z "$num" ] && return 1
  first="$(printf '%s' "$num" | cut -d. -f1)"
  if [ "$first" = "1" ]; then
    printf '%s' "$(printf '%s' "$num" | cut -d. -f2)"
  else
    printf '%s' "$first"
  fi
}

# 收集候选 JDK 路径（按优先级：环境变量 → .env/java.env → 扫描）
JAVA_CANDIDATES=()
[ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] && JAVA_CANDIDATES+=("${JAVA_HOME}")
JAVA_ENV_FILE="$APP_HOME/.env/java.env"
if [ -f "$JAVA_ENV_FILE" ]; then
  JAVA_PINNED="$(grep -E '^[[:space:]]*JAVA_HOME=' "$JAVA_ENV_FILE" 2>/dev/null | head -1 | sed -E 's/^[[:space:]]*JAVA_HOME=[[:space:]]*//' | tr -d "\"'")"
  if [ -n "$JAVA_PINNED" ] && [ -x "$JAVA_PINNED/bin/java" ]; then
    JAVA_CANDIDATES+=("$JAVA_PINNED")
  fi
fi
JAVA_SCAN_BASES=(/usr/local/jdk /opt/java /opt/jdk /Library/Java/JavaVirtualMachines "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" "/c/Program Files/Amazon Corretto")
for jb in "${JAVA_SCAN_BASES[@]}"; do
  [ -d "$jb" ] || continue
  for jd in "$jb"/* "$jb"/*/*; do
    [ -x "$jd/bin/java" ] && JAVA_CANDIDATES+=("$jd")
  done
done
# 去重
JAVA_UNIQ="$(printf '%s\n' "${JAVA_CANDIDATES[@]}" | awk '!seen[$0]++')"

# 版本探测，得到有效 JDK 列表
JAVA_VALID_PATHS=() JAVA_VALID_MAJORS=()
while IFS= read -r jc; do
  [ -n "$jc" ] || continue
  [ -x "$jc/bin/java" ] || continue
  jm="$(java_major "$jc")"
  [ -n "$jm" ] || continue
  JAVA_VALID_PATHS+=("$jc")
  JAVA_VALID_MAJORS+=("$jm")
done <<< "$JAVA_UNIQ"

# 构建可选池：优先 Java 17；无 17 才退而求其次（并告警）
JAVA_POOL_PATHS=() JAVA_POOL_MAJORS=() JAVA_BEST=0 JAVA_HAS17=0
for ji in "${!JAVA_VALID_MAJORS[@]}"; do
  [ "${JAVA_VALID_MAJORS[$ji]}" = "17" ] && JAVA_HAS17=1 && break
done
if [ "$JAVA_HAS17" = "1" ]; then
  for ji in "${!JAVA_VALID_MAJORS[@]}"; do
    [ "${JAVA_VALID_MAJORS[$ji]}" = "17" ] && JAVA_POOL_PATHS+=("${JAVA_VALID_PATHS[$ji]}") && JAVA_POOL_MAJORS+=("${JAVA_VALID_MAJORS[$ji]}")
  done
else
  if [ "${#JAVA_VALID_MAJORS[@]}" -gt 0 ]; then
    JAVA_POOL_PATHS=("${JAVA_VALID_PATHS[@]}"); JAVA_POOL_MAJORS=("${JAVA_VALID_MAJORS[@]}")
    echo "[start] 警告: 未找到 Java 17，将使用 Java ${JAVA_VALID_MAJORS[0]}（应用按 17 编译，可能 UnsupportedClassVersionError）" >&2
  else
    echo "[start] 未找到可用的 JDK。" >&2
    echo "[start] 请任选其一: ① export JAVA_HOME=/path/to/jdk17  ② 在 $JAVA_ENV_FILE 写入 JAVA_HOME=/path/to/jdk17" >&2
    exit 1
  fi
fi

# 多个候选且为交互终端：列出菜单让用户选（默认第一个 17）
if [ "${#JAVA_POOL_PATHS[@]}" -gt 1 ] && [ -t 0 ]; then
  echo "[start] 检测到多个 Java 17，请选择要使用的版本:"
  for ji in "${!JAVA_POOL_PATHS[@]}"; do
    echo "  $((ji+1))) ${JAVA_POOL_PATHS[$ji]}  [Java ${JAVA_POOL_MAJORS[$ji]}]"
  done
  printf "输入序号 [1-%d，默认 1]: " "${#JAVA_POOL_PATHS[@]}"
  read -r JAVA_SEL || true
  JAVA_SEL="${JAVA_SEL:-1}"
  JAVA_IDX=$((JAVA_SEL-1))
  if [ "$JAVA_IDX" -ge 0 ] && [ "$JAVA_IDX" -lt "${#JAVA_POOL_PATHS[@]}" ]; then
    JAVA_BEST=$JAVA_IDX
  fi
fi

JAVA_HOME="${JAVA_POOL_PATHS[$JAVA_BEST]}"
# Windows: 原生 java.exe 不识别 git-bash 的 POSIX 路径(/c/...、/d/...)，
# 必须转成 Windows 原生形式(C:\...)，否则报 "Unable to access jarfile"。
if [ "$OS" = "windows" ]; then
  _jh_win="$(cygpath -w "$JAVA_HOME" 2>/dev/null)"
  [ -n "$_jh_win" ] && JAVA_HOME="$_jh_win"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# ---- 优先使用无空格路径的 Maven（Windows 本地开发）----
if [ "$OS" = "windows" ]; then
  for m in /d/tools/apache-maven-*/bin /c/tools/apache-maven-*/bin; do
    if [ -x "$m/mvn" ]; then
      export PATH="$m:$PATH"
      break
    fi
  done
fi

# ---- 本地开发：启动前重建产物，避免每次起旧 jar ----
if [ "$OS" = "windows" ] && [ "${SPRING_PROFILE:-dev}" != "production" ]; then
  if command -v mvn >/dev/null 2>&1; then
    BUILD_LOG="$APP_HOME/logs/build.log"
    mkdir -p "$APP_HOME/logs"
    echo "[start] 本地开发模式：mvn clean package 重建产物（跳过测试）..."
    if mvn -q clean package -Dmaven.test.skip=true >> "$BUILD_LOG" 2>&1; then
      echo "[start] 构建完成 -> $BUILD_LOG"
    else
      echo "[start] mvn clean package 失败，拒绝启动以免使用旧/损坏产物。" >&2
      echo "[start] ===== build.log 末尾 40 行（ERROR/BUILD 过滤）=====" >&2
      grep -E 'ERROR|BUILD' "$BUILD_LOG" 2>/dev/null | tail -n 40 | sed 's/^/[build] /' >&2 || true
      exit 1
    fi
  else
    echo "[start] 未找到 mvn 命令，跳过自动构建，使用已有 jar" >&2
  fi
fi
# ---- 定位 jar：优先生产部署产物($APP_HOME 根目录)，回退本地 mvn 构建产物(target/) ----
JAR=""
for cand in "$APP_HOME"/${APP_NAME}-*.jar "$APP_HOME"/target/${APP_NAME}-*.jar; do
  if [ -f "$cand" ]; then JAR="$cand"; break; fi
done
if [ -z "$JAR" ]; then
  echo "[start] 未找到 jar 包（已查 $APP_HOME/*.jar 与 $APP_HOME/target/*.jar）" >&2
  echo "[start] 请先部署产物，或本地用 'mvn package' 构建" >&2
  exit 1
fi
# Windows: jar 路径同样需转成 Windows 原生形式
if [ "$OS" = "windows" ]; then
  _jar_win="$(cygpath -w "$JAR" 2>/dev/null)"
  [ -n "$_jar_win" ] && JAR="$_jar_win"
fi
PORT="${1:-2025}"
# 默认探针改走 /actuator/health/liveness（仅验进程存活+磁盘，不依赖 DB / 数据自检），
# 避免「自定义数据自检不通过 / 库慢 / 网络抖」被误判成启动超时。deploy.sh 会显式传该路径。
# 完整健康（含库连通、数据基线）仍走 /actuator/health，供监控使用，不阻断部署。
HEALTH_PATH="${2:-/actuator/health/liveness}"
[ -z "$HEALTH_PATH" ] && HEALTH_PATH="/actuator/health/liveness"
LOGS_DIR="$APP_HOME/logs"
PID_FILE="$LOGS_DIR/app.pid"
LOG_FILE="$LOGS_DIR/app.log"

# ---- 解除 WorkBuddy 客户端注入的 SERVER__PORT=0 对端口的暗算 ----
unset SERVER__PORT SERVER__HOST 2>/dev/null || true

# ---- 凭据来源：与 deploy.sh 一致，优先使用当前 shell 已注入的环境变量 ----
SPRING_PROFILE="${SPRING_PROFILE:-dev}"
ENV_FILE="$APP_HOME/.env/${SPRING_PROFILE}.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  echo "[start] 已从 $ENV_FILE 载入凭据（可选兜底）"
else
  echo "[start] 未发现 $ENV_FILE，将使用当前环境已注入的变量（云效私密变量 / 手动 export）"
fi

# ---- 按 profile 校验必需凭据（各 profile 引用变量不同，避免无谓 fail）----
case "$SPRING_PROFILE" in
  dev)
    : "${DB_PASSWORD:?未提供 DB_PASSWORD：①手动 export ②创建 $ENV_FILE 填入真实密码}"
    ;;
  test|pre)
    : "${DB_USERNAME:?未提供 DB_USERNAME：①手动 export ②创建 $ENV_FILE 填入}"
    : "${DB_PASSWORD:?未提供 DB_PASSWORD：①手动 export ②创建 $ENV_FILE 填入}"
    ;;
  *)
    : "${DB_URL:?未提供 DB_URL：①云效“主机部署”变量区配置 ②手动 export 后执行 ③创建 $ENV_FILE 填入真实凭据}"
    : "${DB_USERNAME:?未提供 DB_USERNAME}"
    : "${DB_PASSWORD:?未提供 DB_PASSWORD}"
    ;;
esac

# ---- 监听指定端口的 PID（跨平台）----
port_pid() {
  local port="$1"
  if [ "$OS" = "windows" ]; then
    netstat -ano 2>/dev/null | awk -v p=":$port " '$0 ~ p && /LISTENING/ {print $5; exit}' || true
  else
    ss -ltnp 2>/dev/null | grep -E "[:.]${port}([[:space:]]|$)" | grep -oE 'pid=[0-9]+' | head -1 | sed 's/pid=//' || true
  fi
}

# ---- 防重复启动 ----
if [ "$OS" = "windows" ]; then
  if [ -n "$(port_pid "$PORT")" ]; then
    echo "[start] 端口 $PORT 已被占用（已有实例运行），请先 bash bin/stop.sh" >&2
    exit 1
  fi
else
  if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
      echo "[start] 已在运行 (PID $OLD_PID from $PID_FILE)，请先停止或改用 restart.sh" >&2
      exit 1
    fi
  fi
  PORT_HOLDER="$(port_pid "$PORT")"
  if [ -n "$PORT_HOLDER" ]; then
    echo "[start] 端口 $PORT 仍被 PID $PORT_HOLDER 占用（stop.sh 未清理干净），拒绝启动以免旧实例继续服务。" >&2
    echo "[start] 请在 ECS 手动清理: kill -KILL $PORT_HOLDER ; 再确认 ss -ltnp | grep :$PORT 无输出" >&2
    exit 1
  fi
fi

# ---- 启动 ----
mkdir -p "$LOGS_DIR"
# 记录启动前的日志行数：就绪后只从「本次启动新增的日志段」里读自检结论。
# 日志是 append 写的，不划基线会读到上一次启动留下的旧结论（典型误判：明明这次库没通，还报 UP）。
LOG_BASELINE=0
if [ -f "$LOG_FILE" ]; then
  LOG_BASELINE="$(wc -l < "$LOG_FILE" 2>/dev/null | tr -d '[:space:]' || true)"
  [ -z "$LOG_BASELINE" ] && LOG_BASELINE=0
fi
echo "[start] 启动 ${APP_NAME} (port=${PORT}, profile=${SPRING_PROFILE}, health=${HEALTH_PATH}) ..."

if [ "$OS" = "linux" ]; then
  setsid "$JAVA_HOME/bin/java" -jar "$JAR" --server.port="$PORT" --spring.profiles.active="$SPRING_PROFILE" >> "$LOG_FILE" 2>&1 < /dev/null &
  JAVA_BG=$!
  disown "$JAVA_BG" 2>/dev/null || true
else
  nohup "$JAVA_HOME/bin/java" -jar "$JAR" --server.port="$PORT" --spring.profiles.active="$SPRING_PROFILE" >> "$LOG_FILE" 2>&1 &
  JAVA_BG=$!
fi

# ---- 健康检查: 轮询 HEALTH_PATH 直到返回 200 ----
# HEALTH_PATH 默认 /actuator/health/liveness（只验进程存活+磁盘，不碰数据库），
# 因此只要 JVM 起来、端口绑定成功即为就绪，不会被「库慢 / 数据自检不通过」误杀。
# STARTUP_TIMEOUT 可调（云效步骤 export STARTUP_TIMEOUT=300 即可），默认 180s 给 prod 冷启留余量。
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
last_code=""
for i in $(seq 1 "$STARTUP_TIMEOUT"); do
  # --max-time 3：防止探针本身阻塞（如库慢时 getConnection 卡到 Hikari connectionTimeout）拖垮整个轮询
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://127.0.0.1:${PORT}${HEALTH_PATH}" 2>/dev/null || true)
  last_code="$code"
  if [ "$code" = "200" ]; then
    PID_NOW="$(port_pid "$PORT")"
    [ -z "$PID_NOW" ] && PID_NOW="$JAVA_BG"
    echo "$PID_NOW" > "$PID_FILE"
    echo "[start] 就绪 (${i}s) -> http://localhost:${PORT}${HEALTH_PATH}"
    echo "[start] 日志: $LOG_FILE  停止: bash bin/stop.sh"
    # ---- 完整健康（含库连通 / 表与种子基线）----
    # 【为什么不再 curl /actuator/health】
    # 2026-10-19 安全收口后该端点要求认证，匿名 curl 只能拿到 401。
    # 401 不是「健康状态」，上一版把它判成「跳过完整检查」等于**把这项能力丢了**：
    # 部署完再也没人能从这里确认库通不通、表与种子基线对不对。
    # 现在改读应用自报：StartupHealthReporter 在启动完成时把 HealthEndpoint 的结论
    # 写进 app.log（同一 JVM 内直读，不走 HTTP、不需要凭证，还能拿到逐组件状态）。
    # 只在「本次启动新增的日志段」里找，避免读到上一次启动的旧结论。
    health_line=""
    for _ in $(seq 1 20); do
      health_line="$(tail -n +$((LOG_BASELINE + 1)) "$LOG_FILE" 2>/dev/null \
        | grep -m1 '\[startup-health\]' || true)"
      [ -n "$health_line" ] && break
      sleep 0.5
    done
    if [ -n "$health_line" ]; then
      echo "[start] 完整健康: $(printf '%s' "$health_line" | sed 's/.*\[startup-health\] //')"
    else
      echo "[start] 提示: 10s 内未拿到启动自检结果，请查看 $LOG_FILE 中的 [startup-health] 行。" >&2
    fi
    exit 0
  fi
  sleep 1
done

# ---- 超时诊断：把真实失败原因打进流水线日志，避免只报一句「启动超时」 ----
echo "[start] 启动超时（${STARTUP_TIMEOUT}s 内未拿到 200），日志: $LOG_FILE" >&2
if [ "$last_code" = "503" ]; then
  echo "[start] 诊断: 端口已监听且探针返回 503 —— 应用进程已起来，但健康状态为 DOWN。" >&2
  echo "[start]       根因几乎一定是 自定义 DatabaseTableHealthIndicator（表数/种子数基线与生产库不一致）" >&2
  echo "[start]       或数据库连通性检查未通过。部署门禁已改用 liveness（仅验存活），不应再因此超时。" >&2
elif [ "$last_code" = "000" ] || [ -z "$last_code" ]; then
  echo "[start] 诊断: 端口始终未监听（curl 拿到 000）—— 应用启动失败（Flyway / DB 连接 / 配置异常）。" >&2
else
  echo "[start] 诊断: 最后 HTTP 状态码 = $last_code" >&2
fi
echo "[start] ===== app.log 末尾 60 行 =====" >&2
tail -n 60 "$LOG_FILE" 2>/dev/null | sed 's/^/[app.log] /' >&2 || true
exit 1
