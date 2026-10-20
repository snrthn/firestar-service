-- ============================================================================
-- V33：修正 V32 字典种子 status 域值（'启用' → 'ENABLED'）
-- 根因：V22__dict_align_real_data.sql 已把全部存量字典 status 归一为英文域值
--       （sys_dict_data.status = ENABLED，前端 useDict 只消费 status==='ENABLED'），
--       V32 照 V2 的旧格式种了中文 '启用' → feedback_status 四条全部被
--       loadDictOptions 过滤 → 状态下拉「无数据」（2026-10-21 虎哥截图实锤）。
-- 影响：sys_dict_type.status 同理归一（字典类型管理页列表会显示原始值）。
-- 幂等：固定值 UPDATE，只碰 feedback_status 两行 type/type_code，不动其他字典。
-- ============================================================================
UPDATE `sys_dict_data` SET `status` = 'ENABLED'
WHERE `type_code` = 'feedback_status' AND `status` <> 'ENABLED';

UPDATE `sys_dict_type` SET `status` = 'ENABLED'
WHERE `type_code` = 'feedback_status' AND `status` <> 'ENABLED';
