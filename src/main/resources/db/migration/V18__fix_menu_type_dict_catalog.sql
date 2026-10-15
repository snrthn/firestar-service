-- ============================================================================
-- V18：归一 menu_type 字典种子值 DIRECTORY → CATALOG
-- 背景（虎哥 2026-10-15 字典消费样板实验）：
--   字典从此不止「管理页自消费」——业务页开始读字典驱动 UI（首个样板：
--   菜单管理弹窗的「菜单类型」单选组）。消费的前提是字典值与代码域值一致，
--   而 V2 种子把「目录」项的 dict_value 种成了 DIRECTORY，后端
--   MenuServiceImpl.applyForm 白名单与前端 menu.vue 用的都是 CATALOG，
--   直接消费字典会把非法值 DIRECTORY 选进表单（保存即被后端拒）。
--   V16 迁移注释里记了这笔历史偏差但划为「非本次范围」，本次消费实验
--   正式把它纳入范围。
-- 幂等：UPDATE 带源值条件，重复执行 0 行副作用；先查唯一键
--   (tenant_id, type_code, dict_value) 防撞已存在的 CATALOG 行。
-- 不动：status / sort_no / is_default / dict_label——「外链」项目前被虎哥
--   手动停用（DISABLED）正是样板实验的观测样本，迁移不强制改回。
-- 依赖：V1（sys_dict_data）、V2（menu_type 种子）、V16（LINK 项）
-- ============================================================================

SET @db = DATABASE();

-- 仅当「同租户下 menu_type 还没有 CATALOG 行」时才把 DIRECTORY 改过来，
-- 避免与 uk_tenant_dict_value(tenant_id, type_code, dict_value) 相撞。
SET @has_catalog = (
    SELECT COUNT(*) FROM `sys_dict_data`
    WHERE `tenant_id` = 0 AND `type_code` = 'menu_type' AND `dict_value` = 'CATALOG'
);
SET @sql = IF(@has_catalog = 0,
    'UPDATE `sys_dict_data` SET `dict_value` = ''CATALOG'' WHERE `type_code` = ''menu_type'' AND `dict_value` = ''DIRECTORY''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
