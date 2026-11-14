#!/usr/bin/env bash
# ============================================================
# 集成测试一键入口（零参数）：重建空测试库 → Flyway 全量重放 → 全量 mvn test
#
# 用法：
#   bash bin/test.sh                       # 全量（推荐）
#   bash bin/test.sh -Dtest=SmokeTest     # 只跑指定测试类
#   bash bin/test.sh --no-reset -Dtest=RbacCrudTest
#                                                             # 沿用现有库，快速重跑
#   bash bin/test.sh --help               # 帮助
#
# 为什么需要脚本（而不是裸敲 mvn test）：
#   1. 干净 shell 缺 DB_PASSWORD —— it profile 的密码刻意留空回退，
#      真实凭据只允许来自 .env/dev.env 或环境变量，不进版本库。
#   2. 裸跑不会重建库，上轮测试数据残留可能让用例撞唯一键 / 种子计数漂移。
#   3. 清掉 SPRING_PROFILES_ACTIVE，避免有人误设 dev/prod 造成混淆。
#   4. 统一注入 IT_DB_URL（集成测试专用变量）并做库名断言，
#      杜绝 .env/dev.env / 云效变量区的 DB_URL 把 JDBC 劫持到 dev / prod 库。
#
# 说明：SPRING_PROFILES_ACTIVE 已不再需要 —— 15 个 @SpringBootTest 统一
#       @ActiveProfiles("it")；JWT / AI_KEK / Coze / 文件存储回退值都在
#       firestar-server/src/test/resources/application-it.properties 里。
#
# 前提：本机可达 MySQL（默认 127.0.0.1:3306），凭据来自 .env/dev.env 或环境变量。
# 集成测试连的是 firestar_test（it profile），**不会写 dev 库 firestar**。
# ⚠️ 目标实例只能是本地或独立测试实例，禁止指向线上生产 MySQL：
#    全量 Flyway 重放（含 Flowable 62 张表）会写 binlog、占连接与磁盘 IO。
#    本脚本只断言库名，不校验实例归属，实例选择由使用者负责。
# CI（云效 alinux3 构建容器）无 MySQL 实例，仍只跑纯单测，见 docs/云效率流水线配置.yaml。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# ---- 输出落盘：每次运行覆盖式记录全部输出（含中途轻量异常），便于事后排查 ----
# 用 tee 但不带 -a => 目标文件在启动时即被截断，重跑即覆盖上一次，不累加写入。
mkdir -p logs
IT_LOG="logs/it.log"
exec > >(tee "$IT_LOG") 2>&1
echo "[it] 完整输出已记录到 ${IT_LOG}（每次运行覆盖，不累加）"

RESET=1
MVN_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --help|-h)
      awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
      exit 0
      ;;
    --no-reset)
      RESET=0
      ;;
    *)
      MVN_ARGS+=("$arg")
      ;;
  esac
done

# -Dtest=XXX 会让 core / biz 模块因「没有匹配的测试」直接失败，自动补容错开关。
if [ ${#MVN_ARGS[@]} -gt 0 ]; then
  HAS_DTEST=0
  HAS_SUREFIRE_FLAG=0
  for a in "${MVN_ARGS[@]}"; do
    case "$a" in
      -Dtest=*) HAS_DTEST=1 ;;
      -Dsurefire.failIfNoSpecifiedTests=*) HAS_SUREFIRE_FLAG=1 ;;
    esac
  done
  if [ "$HAS_DTEST" = 1 ] && [ "$HAS_SUREFIRE_FLAG" = 0 ]; then
    MVN_ARGS+=("-Dsurefire.failIfNoSpecifiedTests=false")
  fi
fi

# ---- 凭据载入：.env/dev.env 只提供账号/密码，绝不继承它的 DB_URL ----
if [ -f .env/dev.env ]; then
  set -a
  . ./.env/dev.env
  set +a
fi
: "${DB_PASSWORD:?需要 DB_PASSWORD（放在 .env/dev.env，或作为环境变量注入）}"

# example.env 把可选覆盖键也写成 __FILL_ME__ 占位；它不是真实凭据，
# 若照抄进 dev.env 会让 mysql / JDBC 拿占位账号密码去连库。这里忽略掉，
# 让它们按 application-it.properties 的链路正常回落。
if [[ "${TEST_DB_USERNAME:-}" == "__FILL_ME__" ]]; then unset TEST_DB_USERNAME; fi
if [[ "${TEST_DB_PASSWORD:-}" == "__FILL_ME__" ]]; then unset TEST_DB_PASSWORD; fi

# ---- [R1/R2 防呆] 库名唯一真源 + 三重断言（2026-11-14）----
# 根因：application-it.properties 原本用 ${DB_URL:...}，而 dev.env / 云效变量区
#       的 DB_URL 指向 dev 库 firestar / 生产库；一旦导出就劫持 JDBC，
#       reset_test_db.sql 又 DROP 了无关的 firestar_test，结果是
#       「重建 A 库、却在 B 库跑 Flyway 迁移并写入测试数据」。
# 修法：it profile 改用专用变量 IT_DB_URL（与部署用 DB_URL 彻底解耦），
#       本脚本统一注入；SQL 库名也由同一变量派生，TEST_DB_NAME 成为真开关。
DB_HOST_EFF="${TEST_DB_HOST:-${DB_HOST:-127.0.0.1}}"
DB_PORT_EFF="${TEST_DB_PORT:-${DB_PORT:-3306}}"
DB_NAME_EFF="${TEST_DB_NAME:-firestar_test}"
DB_USER_EFF="${TEST_DB_USERNAME:-${DB_USERNAME:-root}}"

# 断言 1：库名白名单 —— 只含 [A-Za-z0-9_] 且以 _test 结尾。
# 同时挡住 sed 注入 / 反引号库名，确保 DROP DATABASE 永不落到 dev / prod 库。
if [[ ! "$DB_NAME_EFF" =~ ^[A-Za-z0-9_]+_test$ ]]; then
  echo "[it][FATAL] 目标库名 '${DB_NAME_EFF}' 非法：只接受字母/数字/下划线且必须以 _test 结尾" >&2
  echo "[it][FATAL] 已拒绝 DROP / 连接，防止误删 dev 库 firestar 或生产库" >&2
  exit 1
fi
if [[ ! "$DB_PORT_EFF" =~ ^[0-9]+$ ]] || [[ ! "$DB_HOST_EFF" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "[it][FATAL] 目标 host/port 非法：host='${DB_HOST_EFF}' port='${DB_PORT_EFF}'" >&2
  exit 1
fi

# 断言 2：显式构造测试库 JDBC URL，覆盖一切外部注入；并清掉 Spring 原生
#         relaxed-binding 变量（优先级高于 properties 文件，残留同样会劫持）。
IT_JDBC_PARAMS='useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true'
export IT_DB_URL="jdbc:mysql://${DB_HOST_EFF}:${DB_PORT_EFF}/${DB_NAME_EFF}?${IT_JDBC_PARAMS}"
unset DB_URL SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD || true
export TEST_DB_USERNAME="$DB_USER_EFF"
export TEST_DB_PASSWORD="$DB_PASSWORD"

# 断言 3：回读 URL，确认库名与唯一真源一致（防拼接错误 / 参数污染）
case "$IT_DB_URL" in
  *"/${DB_NAME_EFF}?"*) ;;
  *) echo "[it][FATAL] IT_DB_URL 拼接异常，库名与断言不一致，拒绝继续" >&2; exit 1 ;;
esac

echo "[it] profile=it（@ActiveProfiles 固定，无需 SPRING_PROFILES_ACTIVE）"
echo "[it] 目标库=${DB_NAME_EFF} @ ${DB_HOST_EFF}:${DB_PORT_EFF}  user=${DB_USER_EFF}"
echo "[it] IT_DB_URL 由本脚本注入；外部 DB_URL / dev.env / 云效变量一律不继承"

if [ "$RESET" = 1 ]; then
  command -v mysql >/dev/null 2>&1 || {
    echo "[it] 未找到 mysql CLI，无法重建空库。可改用 --no-reset 沿用现有库，或自行重建 ${DB_NAME_EFF}";
    exit 1;
  }
  # SQL 模板里的 {{IT_DB_NAME}} 由本脚本注入（库名已过断言 1）。
  # 注入后复查占位符无残留、且 SQL 确实含目标库名，避免误建无用空库。
  IT_RESET_SQL="$(sed "s|{{IT_DB_NAME}}|${DB_NAME_EFF}|g" scripts/reset_test_db.sql)"
  if [[ "$IT_RESET_SQL" == *'{{IT_DB_NAME}}'* ]] || [[ "$IT_RESET_SQL" != *"${DB_NAME_EFF}"* ]]; then
    echo "[it][FATAL] reset SQL 库名注入失败，拒绝执行 DROP" >&2
    exit 1
  fi
  echo "[it] 重建空库 ${DB_NAME_EFF} ..."
  printf '%s\n' "$IT_RESET_SQL" \
    | MYSQL_PWD="$DB_PASSWORD" mysql --default-character-set=utf8mb4 \
        -h "$DB_HOST_EFF" -P "$DB_PORT_EFF" -u "$DB_USER_EFF"
  echo "[it] 空库就绪；启动测试后由 Flyway 全量重放 schema + 种子"
else
  echo "[it] --no-reset：沿用现有库（可能含上轮残留数据，失败时请去掉该参数重跑）"
fi

unset SPRING_PROFILES_ACTIVE || true
START=$SECONDS
if [ ${#MVN_ARGS[@]} -gt 0 ]; then
  mvn -o test "${MVN_ARGS[@]}"
else
  mvn -o test
fi
echo "[it] 完成，耗时 $((SECONDS - START))s"
