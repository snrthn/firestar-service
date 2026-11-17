#!/usr/bin/env bash
#
# 重建知识库切片的 MySQL ngram 全文索引（运维脚本，可重复执行）
# 位置约定: bin/rebuild-kb-ft-index.sh
#
# 用法:
#   bash bin/rebuild-kb-ft-index.sh [profile] [--probe '查询词']
#   bash bin/rebuild-kb-ft-index.sh                 # 默认 dev，仅重建 + 健康自检
#   bash bin/rebuild-kb-ft-index.sh prod            # 生产环境（读 .env/prod.env）
#   bash bin/rebuild-kb-ft-index.sh dev --probe '飞星报销'  # 重建后跑一次召回探针
#
# 【为什么需要这个脚本】
#   2026-11-17 端到端真机验收发现：sys_kb_chunk 的 FULLTEXT(ngram) 索引在批量导入/
#   迁移数据后可能处于"未同步"状态——MATCH...AGAINST 恒返回 0，但同结构新建表灌
#   同样数据立刻能召回，OPTIMIZE TABLE 重建后恢复。Flyway 迁移只在版本升级时跑一次，
#   盖不住"未来每次批量导入"，所以提供这个可重复执行的运维脚本。
#
# 【何时执行】
#   - 任何对 sys_kb_chunk 的批量 INSERT / 数据迁移 / 大版本升级之后
#   - 现象层面：用户反馈 AI 助手"查不到知识"，但文档已发布、切片 status=1、ACL 正常
#   - 建议纳入部署流程：凡涉及知识库切片数据的批量变更后调用一次
#
# 【安全说明】
#   本脚本只做一件事：OPTIMIZE TABLE sys_kb_chunk（MySQL 官方重建 InnoDB 全文索引
#   的标准操作，等价 ALTER TABLE ... FORCE）。不改表结构、不删任何数据。
#   ⚠️ OPTIMIZE 在大表上会重建表并可能短暂锁表，生产环境请在低峰期执行。
#
set -euo pipefail

# ---- 定位项目根目录 (bin/ 的上一级) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$APP_HOME"

# ---- 参数解析：第一个非 flag 参数 = profile；--probe 后跟查询词 ----
PROFILE="${1:-dev}"
case "$PROFILE" in
  --*) PROFILE="dev" ;;   # 首个参数就是 --probe 时，profile 用默认 dev
esac
PROBE=""
PROBE_SET=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --probe)
      shift
      if [ "$#" -eq 0 ]; then echo "[kb-fts] --probe 缺少查询词" >&2; exit 2; fi
      PROBE="$1"; PROBE_SET=1
      ;;
    --probe=*)
      PROBE="${1#--probe=}"; PROBE_SET=1
      ;;
    dev|test|pre|prod|production)
      PROFILE="$1"
      ;;
    *) echo "[kb-fts] 忽略未知参数: $1" >&2 ;;
  esac
  shift
done

# ---- OS 探测 ----
OS_RAW="$(uname -s 2>/dev/null || echo unknown)"
case "$OS_RAW" in
  MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
  *)                     OS="linux" ;;
esac
[ "$OS" = "windows" ] && export MSYS_NO_PATHCONV=1

# ---- 凭据加载：优先当前环境变量（云效/CI 注入），否则读 .env/{profile}.env ----
# 与 start.sh / deploy.sh 同款 load_env（逐行 export，避免 & 被当作后台分隔符）
load_env() {
  local f="$1"
  [ -f "$f" ] || return 0
  local line key val
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line#"${line%%[![:space:]]*}"}"
    [ -z "$line" ] && continue
    case "$line" in \#*) continue ;; esac
    line="${line%%[[:space:]]#*}"
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"; val="${line#*=}"
    [ -z "$key" ] && continue
    case "$key" in
      *[!A-Za-z0-9_]*) continue ;;
      [0-9]*)          continue ;;
      *) [ -z "${!key:-}" ] && export "$key=$val" ;;   # 环境变量已注入则不覆盖
    esac
  done < "$f"
}
ENV_FILE="$APP_HOME/.env/${PROFILE}.env"
if [ -f "$ENV_FILE" ]; then
  load_env "$ENV_FILE"
  echo "[kb-fts] 已从 $ENV_FILE 载入凭据（已注入的环境变量优先）"
fi

# ---- 必需凭据 ----
: "${DB_URL:?缺少 DB_URL：①环境变量注入 ②创建 $ENV_FILE 填入}"
: "${DB_USERNAME:?缺少 DB_USERNAME}"
: "${DB_PASSWORD:?缺少 DB_PASSWORD}"

case "$DB_URL" in
  jdbc:mysql://*) ;;
  *) echo "[kb-fts][FATAL] DB_URL 不是 jdbc:mysql:// 前缀，拒绝执行（DB_URL=${DB_URL%%\?*}）" >&2; exit 1 ;;
esac
DB_TARGET_HOST="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://([^:/]+).*#\1#' | tr -d '\r')"
DB_TARGET_PORT="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://[^:/]+:([0-9]+).*#\1#' | tr -d '\r')"
case "$DB_TARGET_PORT" in ''|*[!0-9]*) DB_TARGET_PORT=3306 ;; esac
DB_TARGET_NAME="$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#' | tr -d '\r')"

# ---- mysql CLI 定位 ----
MYSQL_CLI=""
for cand in /usr/local/mysql80/bin/mysql /usr/local/mysql/bin/mysql; do
  [ -x "$cand" ] && MYSQL_CLI="$cand" && break
done
[ -z "$MYSQL_CLI" ] && MYSQL_CLI="$(command -v mysql 2>/dev/null || true)"
if [ -z "$MYSQL_CLI" ]; then
  echo "[kb-fts][FATAL] 找不到 mysql CLI，无法重建全文索引" >&2
  exit 1
fi

mysql_q() {
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLI" --default-character-set=utf8mb4 \
    -h "$DB_TARGET_HOST" -P "$DB_TARGET_PORT" -u "$DB_USERNAME" "$DB_TARGET_NAME" \
    -N -e "$1" 2>&1
}

echo "[kb-fts] 目标库: ${DB_TARGET_HOST}:${DB_TARGET_PORT}/${DB_TARGET_NAME} (user=$DB_USERNAME)"
echo "[kb-fts] 表: sys_kb_chunk  FULLTEXT KEY ft_kb_chunk (heading_path, content) WITH PARSER ngram"

# ---- 前置检查：表存在 + 全文索引在 ----
if ! mysql_q "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_TARGET_NAME' AND TABLE_NAME='sys_kb_chunk';" | grep -q '^1$'; then
  echo "[kb-fts][FATAL] 表 sys_kb_chunk 不存在（可能 Flyway 未迁移到 V207）" >&2
  exit 1
fi
if ! mysql_q "SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$DB_TARGET_NAME' AND TABLE_NAME='sys_kb_chunk' AND INDEX_NAME='ft_kb_chunk' AND INDEX_TYPE='FULLTEXT';" | grep -q '^1$'; then
  echo "[kb-fts][FATAL] sys_kb_chunk 缺少 FULLTEXT 索引 ft_kb_chunk（检查 V207 迁移）" >&2
  exit 1
fi

ROWS_BEFORE="$(mysql_q "SELECT COUNT(*) FROM sys_kb_chunk;")"
echo "[kb-fts] 重建前行数: $ROWS_BEFORE"

# ---- 核心：重建全文索引 ----
echo "[kb-fts] 执行 OPTIMIZE TABLE sys_kb_chunk ..."
mysql_q "OPTIMIZE TABLE sys_kb_chunk;" || {
  echo "[kb-fts][FATAL] OPTIMIZE TABLE 失败" >&2
  exit 1
}

ROWS_AFTER="$(mysql_q "SELECT COUNT(*) FROM sys_kb_chunk;")"
echo "[kb-fts] 重建后行数: $ROWS_AFTER"
if [ "$ROWS_BEFORE" != "$ROWS_AFTER" ]; then
  echo "[kb-fts][FATAL] 重建前后行数不一致（$ROWS_BEFORE -> $ROWS_AFTER），请人工核查！" >&2
  exit 1
fi

# ---- 索引同步状态自检 ----
# 注意：INNODB_FT_CONFIG 只有先 SET GLOBAL innodb_ft_aux_table 指向本表才有意义，
# 否则读到的是上一次设置残留的别的表数据（真机验收踩过这个坑）。KEY 是保留字，须加反引号。
mysql_q "SET GLOBAL innodb_ft_aux_table='$DB_TARGET_NAME/sys_kb_chunk';" >/dev/null 2>&1 || true
echo "[kb-fts] 全文索引同步状态 INNODB_FT_CONFIG (aux_table=$DB_TARGET_NAME/sys_kb_chunk):"
mysql_q "SELECT \`KEY\`, VALUE FROM information_schema.INNODB_FT_CONFIG WHERE \`KEY\` IN ('index_size','synced_doc_id');" 2>&1 | sed 's/^/  /' || echo "  (读取失败，不影响重建结果)"

# ---- 可选：召回探针（需传一个库里确实存在的词）----
if [ "$PROBE_SET" -eq 1 ]; then
  echo "[kb-fts] 召回探针: MATCH(content, heading_path) AGAINST('$PROBE' IN BOOLEAN MODE)"
  HITS="$(mysql_q "SELECT COUNT(*) FROM sys_kb_chunk WHERE MATCH(content, heading_path) AGAINST('$PROBE' IN BOOLEAN MODE);")"
  echo "[kb-fts] 探针命中: $HITS"
  if [ "$HITS" = "0" ]; then
    echo "[kb-fts][WARN] 探针 0 命中——可能这个词不在库里，或索引仍有不同步。换一个库里确实存在的词再试。" >&2
  fi
fi

echo "[kb-fts] 完成。sys_kb_chunk 全文索引已重建，行数未变。"
