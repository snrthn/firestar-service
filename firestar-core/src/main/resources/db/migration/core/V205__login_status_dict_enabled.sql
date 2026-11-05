-- ===========================================================================
-- 纠正 login_status 字典 LOCKED / UNLOCKED 的状态值（2026-11-05 紧急修复）
--
-- 问题根因：V202 初版误用 status='启用'（中文），且 dev / 测试存量行实际为 'DISABLED'。
--   前端 useDict.ts:73 消费过滤只认 status==='ENABLED'（见 composable 顶部【约定 2】），
--   这两条被过滤后，登录日志页「结果」列只能 fallback 显示原始枚举 LOCKED / UNLOCKED。
--   （此前误判为「部署时机未跑迁移」，实为状态值不符合消费约定——字典计数测试只验数量不验
--     可消费性，故 82/82 绿里藏着这个漏洞。）
--
-- 修复双保险：
--   ① V202 种子已改为 'ENABLED'（对任意「从空库全量重放」的场景生效，包括 bin/test.sh）；
--   ② 本迁移 UPDATE 已存在的存量行 → 'ENABLED'（幂等，对全新库 WHERE 命中 0 行即 no-op）。
--
-- 同步项：仅改存量行的 status 值，无新增行 / 表 / 字典类型 → DatabaseCheckService 计数常量不变。
-- ===========================================================================

UPDATE `sys_dict_data`
SET `status` = 'ENABLED'
WHERE `type_code` = 'login_status'
  AND `dict_value` IN ('LOCKED', 'UNLOCKED')
  AND `status` <> 'ENABLED';
