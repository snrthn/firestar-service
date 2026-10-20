-- ============================================================
-- V31 通知模板增强（P2 打磨，虎哥 2026-10-20 拍板）
-- 1. sys_notification_template 新增 template_name（模板名称，中文展示用）
-- 2. 给 7 个存量模板种中文名称
-- 3. sys_menu id=32「通知模板」改 visible=0 隐藏（侧栏不展示，
--    但 /auth/menus 授权链路照旧返回 → 路由照常注册，
--    通知记录页「通知模板」按钮可跳转，前端 router/index.ts:55-56 注释即此机制）
-- 幂等：ADD COLUMN 走 procedure + information_schema 守卫（同 V27 血案教训）；
--       名称种子走 ON DUPLICATE KEY UPDATE；菜单 visible 固定值 UPDATE。
-- ============================================================

-- ---------- 1. template_name 列（守卫式幂等） ----------
DROP PROCEDURE IF EXISTS add_col_sys_notification_template_template_name;

DELIMITER $$
CREATE PROCEDURE add_col_sys_notification_template_template_name()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'sys_notification_template'
          AND COLUMN_NAME = 'template_name'
    ) THEN
        ALTER TABLE `sys_notification_template`
            ADD COLUMN `template_name` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模板名称（中文，下拉展示用）' AFTER `code`;
    END IF;
END$$

DELIMITER ;

CALL add_col_sys_notification_template_template_name();
DROP PROCEDURE IF EXISTS add_col_sys_notification_template_template_name;

-- ---------- 2. 存量模板中文名称种子（幂等：重复执行不覆盖人工改名之外的值） ----------
-- 注意：只回填名称为空的行，不强制覆盖——运营在管理端改过名的模板不被迁移重置。
UPDATE `sys_notification_template` SET `template_name` = '待审批通知'   WHERE `code` = 'APPROVAL_TODO'   AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '审批结果通知' WHERE `code` = 'APPROVAL_RESULT' AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '回款审核通知' WHERE `code` = 'RECEIPT_AUDIT'   AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '开票完成通知' WHERE `code` = 'INVOICE_DONE'    AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '预算预警通知' WHERE `code` = 'BUDGET_ALERT'    AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '借款逾期通知' WHERE `code` = 'LOAN_OVERDUE'    AND (`template_name` IS NULL OR `template_name` = '');
UPDATE `sys_notification_template` SET `template_name` = '系统公告'     WHERE `code` = 'SYSTEM_NOTICE'   AND (`template_name` IS NULL OR `template_name` = '');

-- ---------- 3. 菜单 id=32「通知模板」隐藏（固定值 UPDATE，幂等） ----------
UPDATE `sys_menu` SET `visible` = 0 WHERE `id` = 32 AND `menu_name` = '通知模板';
