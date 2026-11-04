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
#
# 说明：SPRING_PROFILES_ACTIVE 已不再需要 —— 15 个 @SpringBootTest 统一
#       @ActiveProfiles("it")；JWT / AI_KEK / Coze / 文件存储回退值都在
#       firestar-server/src/test/resources/application-it.properties 里。
#
# 前提：本机可达 MySQL（默认 127.0.0.1:3306），凭据来自 .env/dev.env 或环境变量。
# 集成测试连的是 firestar_test（it profile），**不会写 dev 库 firestar**。
# CI（云效 alinux3 构建容器）无 MySQL 实例，仍只跑纯单测，见 docs/云效率流水线配置.yaml。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

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

if [ -f .env/dev.env ]; then
  set -a
  . ./.env/dev.env
  set +a
fi
: "${DB_PASSWORD:?需要 DB_PASSWORD（放在 .env/dev.env，或作为环境变量注入）}"

DB_HOST_EFF="${TEST_DB_HOST:-${DB_HOST:-127.0.0.1}}"
DB_PORT_EFF="${TEST_DB_PORT:-${DB_PORT:-3306}}"
DB_NAME_EFF="${TEST_DB_NAME:-firestar_test}"
DB_USER_EFF="${TEST_DB_USERNAME:-${DB_USERNAME:-root}}"

echo "[it] profile=it（@ActiveProfiles 固定，无需 SPRING_PROFILES_ACTIVE）"
echo "[it] 目标库=${DB_NAME_EFF} @ ${DB_HOST_EFF}:${DB_PORT_EFF}  user=${DB_USER_EFF}"

if [ "$RESET" = 1 ]; then
  command -v mysql >/dev/null 2>&1 || {
    echo "[it] 未找到 mysql CLI，无法重建空库。可改用 --no-reset 沿用现有库，或自行重建 firestar_test";
    exit 1;
  }
  echo "[it] 重建空库 ${DB_NAME_EFF} ..."
  MYSQL_PWD="$DB_PASSWORD" mysql --default-character-set=utf8mb4 \
    -h "$DB_HOST_EFF" -P "$DB_PORT_EFF" -u "$DB_USER_EFF" \
    < scripts/reset_test_db.sql
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
