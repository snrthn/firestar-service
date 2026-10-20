-- ============================================================================
-- V35：帮助中心「分类」改走字典（虎哥 2026-10-21）
--   1. 字典 help_category（维护页新建/编辑弹窗消费，阅读页分组标题按 label 展示）
--   2. sys_help_article 存量中文分类回填为字典 value（V32 种子是中文，字典化后需归一）
--
-- 依赖：V32（sys_help_article 表 + FAQ 种子）
--
-- ⚠️ 铁律：
-- ① 字典 status 必须写 'ENABLED'（V22 已归一为英文域值，写中文 '启用' 会被
--    useDict 过滤导致下拉空 —— V33 修的就是这个坑，别再踩第二次）
-- ② 字典 value 直接进表单/存库（useDict 约定 4），故存量中文值必须在同一版本迁移
--    里回填成英文码，否则阅读页/维护页列表会回退显示原始英文码或中文混排
-- ③ 只 INSERT 新行 + 固定值 UPDATE，幂等可重跑
-- ============================================================================

-- ========== 1. 字典：帮助分类 ==========
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('help_category', '帮助分类', 'ENABLED', '帮助中心条目的分类：账号与登录/系统使用/常见操作/数据与报表/其他', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`) VALUES
('help_category', '账号与登录', 'ACCOUNT',   1, 'primary', 0, 'ENABLED', 0),
('help_category', '系统使用',   'USAGE',     2, 'primary', 0, 'ENABLED', 0),
('help_category', '常见操作',   'OPERATION', 3, 'success', 0, 'ENABLED', 0),
('help_category', '数据与报表', 'DATA',      4, 'warning', 0, 'ENABLED', 0),
('help_category', '其他',       'OTHER',     5, 'info',    1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `style` = VALUES(`style`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);

-- ========== 2. 存量中文分类 → 字典 value（V32 种子三条，幂等） ==========
UPDATE `sys_help_article` SET `category` = 'ACCOUNT'   WHERE `category` = '账号与登录';
UPDATE `sys_help_article` SET `category` = 'USAGE'     WHERE `category` = '系统使用';
UPDATE `sys_help_article` SET `category` = 'OPERATION' WHERE `category` = '常见操作';
-- 空分类兜底（维护页新建时若被清空，落库为空串会让阅读页分组出现空标题）
UPDATE `sys_help_article` SET `category` = 'OTHER' WHERE `category` IS NULL OR `category` = '';
