-- ============================================================================
-- V43：字典 status 归一化收口（V38 回退修复）
--
-- 背景：V2 时代种子把 sys_dict_data.status 写成中文「启用」，V7/V17/V20/V33
-- 先后归一化为 ENABLED/DISABLED（代码与前端 useDict 均按 ENABLED 消费）。
-- V38 新建 project_approval_status 字典时回退写成中文「启用」，且其后再无
-- 归一化迁移，导致：
--   1. ProjectServiceImpl.approvalStatusNames() 按 status='ENABLED' 过滤
--      查不到 6 行 → approvalStatusName 恒空 → 立项列表显示原始 code；
--   2. 前端 DictSelect/useDictLabels 过滤 status==='ENABLED' → 该类型
--      （及 audit_operation 的 SUBMIT/REJECT 两行）选项/标签同样落空。
-- 本迁移按 V7/V17/V20/V33 先例把残留中文值洗成英文，不动任何计数。
--
-- 幂等：纯 UPDATE，可重复执行。
-- ============================================================================

UPDATE `sys_dict_data` SET `status` = 'ENABLED'  WHERE `status` = '启用';
UPDATE `sys_dict_data` SET `status` = 'DISABLED' WHERE `status` = '停用';
UPDATE `sys_dict_type` SET `status` = 'ENABLED'  WHERE `status` = '启用';
UPDATE `sys_dict_type` SET `status` = 'DISABLED' WHERE `status` = '停用';
