-- ============================================================================
-- V74：shipment_approval_status 字典 status 归一（修复 V73 的回归）。
--
-- 背景（铁证）：V73 把 sys_dict_type / sys_dict_data 的 status 写成中文 '启用'，
-- 而全库约定（见 V17 fix_dict_status_normalize）与前端 useDictLabels 的硬过滤
-- item.status === 'ENABLED' 一致要求英文 'ENABLED'。V73 的 6 条数据 + 1 条类型
-- 全被前端过滤掉 → 审批状态列回退显示原始英文 APPROVING/APPROVED。
-- 后端 applyDataCheck（DictServiceImpl:267）也只认 ENABLED/DISABLED，'启用' 属非法值。
--
-- 设计：不回改 V73（已执行，改内容会触发 Flyway checksum 失配、后端启动失败），
-- 另起本归一化迁移，与 V17 同范式。幂等：仅对仍是中文 '启用' 的行生效，
-- 重复执行无副作用（不影响 dictDataCount，DatabaseCheckService 不跟踪字典行数）。
-- ============================================================================

-- 1. 字典类型
UPDATE `sys_dict_type`
SET `status` = 'ENABLED'
WHERE `type_code` = 'shipment_approval_status' AND `status` = '启用';

-- 2. 字典数据 6 项
UPDATE `sys_dict_data`
SET `status` = 'ENABLED'
WHERE `type_code` = 'shipment_approval_status' AND `status` = '启用';
