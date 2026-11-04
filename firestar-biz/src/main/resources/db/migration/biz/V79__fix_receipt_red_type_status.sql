-- ============================================================================
-- V79：修复 V78 字典 status 写汉字的纪律违规
-- 背景：V78 在新增 receipt_red_type 字典类型时，误将 status 写成汉字'启用'，
--       违反项目纪律（字典 status 必须英文 ENABLED/DISABLED，参考 V10/V17/V20 历史修复）。
--       写汉字会被前端 useDict 按 status='ENABLED' 过滤 → 下拉为空、探针不通过。
-- 修复：统一为 ENABLED。
-- 幂等：仅当当前非 ENABLED 才更新（已修复的库 / 全新库均安全）。
-- 注意：V78 已执行且 Flyway 校验和已锁定，故另起本版本，不回改 V78 源文件。
-- ============================================================================

UPDATE `sys_dict_type`
SET `status` = 'ENABLED', `updated_at` = CURRENT_TIMESTAMP
WHERE `type_code` = 'receipt_red_type' AND `status` <> 'ENABLED';
