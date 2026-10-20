# Flyway 迁移纪律（改这个文件不影响 checksum — 迁移校验只扫 *.sql）

> ⚠️ **已应用的迁移脚本，一个字符都不能再改**（注释也算）。
> Flyway 会对 `flyway_schema_history` 里已登记的脚本做 checksum 校验，
> 改了就报 `Migration checksum mismatch`，`flywayInitializer` 起不来 →
> Web 容器起不来 → 表现为「启动失败/启动超时」。
> 2026-10-21 就因「给 V32/V34/V35 加注释提醒」把本地启动搞挂过一次。
> 真要改语义：写**新版本**迁移（V36、V37…），不要回头改旧的。
> 备注/说明一律写在本 README 或 `DatabaseCheckService` 的注释里。

## 新增字典类迁移必须同步两个常量

在 `DatabaseCheckService` 里（不同步会让 `/system/db-check` 报警）：

1. `readSeedData()` → `dictTypeCount` / `dictDataCount`（当前 **31 / 122**）
2. `EXPECTED_TABLE_COUNT`（当前 **36**，仅新增表时才动）

账目（每次改字典都往下记一笔）：

| 版本 | 内容 | dictTypeCount | dictDataCount |
|---|---|---|---|
| V24 | file_type 补 AUDIO/VIDEO | 28 | 106 |
| V32 | feedback_status（+1 类型 / +4 项） | 29 | 110 |
| V34 | feedback_category（+1 类型 / +7 项） | 30 | 117 |
| V35 | help_category（+1 类型 / +5 项） | **31** | **122** |

历史误报：V19 / V20 / V22 各一次，V32+V34+V35 于 2026-10-21 一次性暴露
（报「应为 28 实际 31」，旧文案甩锅「V2 未完整执行」，排查被带偏 —— 文案已修）。

## 其它铁律

1. 字典 `status` 必须写 `ENABLED`（写中文 `启用` 会被前端 `useDict` 过滤 → 下拉空，V33 修过）。
2. `ADD COLUMN` 走 procedure + `information_schema` 守卫（MySQL 没有 `IF NOT EXISTS`）。
3. 表前缀只能是 `fin_` / `sys_`（`BUSINESS_TABLE_PREFIXES` 只认这两个）。
4. 脚本要幂等（`ON DUPLICATE KEY UPDATE` / 固定值 UPDATE），因为 dev 库常被手工执行一遍，
   之后 Flyway 还会再跑一次（此时脚本已在 history 里是另一回事，未登记的会正常执行一次）。
5. 本地手工执行脚本用 python subprocess 喂 mysql stdin；**mysql CLI 经 PowerShell 管道会破坏反引号 SQL**。
