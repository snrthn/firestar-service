-- ============================================================================
-- V34：问题反馈「问题类别」（虎哥 2026-10-21）
--   1. sys_feedback 新增 category 列（默认值 OTHER，存量行回填）
--   2. 字典 feedback_category（新建/筛选双端消费，值域与后端校验一致）
-- 依赖：V32（sys_feedback 表）
--
-- ⚠️ 铁律：
-- ① ADD COLUMN 走 procedure + information_schema 守卫（V27 血案教训，MySQL DDL 无 IF NOT EXISTS）
-- ② 字典 status 必须写 'ENABLED'（V22 已归一为英文域值，写中文 '启用' 会被 useDict
--    过滤导致下拉空 —— V33 修的就是这个坑，别再踩）
-- ③ 只 INSERT 新行 + 固定值 UPDATE，不改既有业务行（除 category 回填）
-- ============================================================================

DROP PROCEDURE IF EXISTS add_col_sys_feedback_category;

DELIMITER $$
CREATE PROCEDURE add_col_sys_feedback_category()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'sys_feedback'
          AND COLUMN_NAME = 'category'
    ) THEN
        ALTER TABLE `sys_feedback`
            ADD COLUMN `category` VARCHAR(30) NOT NULL DEFAULT 'OTHER' COMMENT '问题类别（字典 feedback_category）' AFTER `status`;
    END IF;
END$$

DELIMITER ;

CALL add_col_sys_feedback_category();
DROP PROCEDURE IF EXISTS add_col_sys_feedback_category;

-- 存量反馈回填为「其他」（建表时无该列，默认 OTHER 已生效，此处幂等兜底）
UPDATE `sys_feedback` SET `category` = 'OTHER' WHERE `category` IS NULL OR `category` = '';

-- ========== 字典：问题类别 ==========
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `status`, `remark`, `tenant_id`) VALUES
('feedback_category', '问题类别', 'ENABLED', '问题反馈的分类：功能异常/界面问题/数据问题/性能问题/账号权限/需求建议/其他', 0)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `status` = VALUES(`status`);

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `style`, `is_default`, `status`, `tenant_id`) VALUES
('feedback_category', '功能异常', 'BUG',        1, 'danger',  0, 'ENABLED', 0),
('feedback_category', '界面问题', 'UI',         2, 'warning', 0, 'ENABLED', 0),
('feedback_category', '数据问题', 'DATA',       3, 'warning', 0, 'ENABLED', 0),
('feedback_category', '性能问题', 'PERF',       4, 'warning', 0, 'ENABLED', 0),
('feedback_category', '账号权限', 'ACCOUNT',    5, 'primary', 0, 'ENABLED', 0),
('feedback_category', '需求建议', 'FEATURE',    6, 'success', 0, 'ENABLED', 0),
('feedback_category', '其他',     'OTHER',      7, 'info',    1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label` = VALUES(`dict_label`), `sort_no` = VALUES(`sort_no`), `style` = VALUES(`style`), `is_default` = VALUES(`is_default`), `status` = VALUES(`status`);
