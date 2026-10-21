-- ============================================================================
-- V37：菜单【初始化与重置】→【系统重置】，路由 /system/init-reset → /system/reset
-- 背景：V36 已部署到生产库，直接改 V36 源文件会导致 Flyway checksum 校验失败、
--       下次启动直接 FAIL。故此处用增量 UPDATE 修正已存在的数据；
--       全新库则先由 V36 插入旧值、再由本脚本 UPDATE 为新值，结果一致。
-- ============================================================================

UPDATE `sys_menu`
SET `menu_name` = '系统重置',
    `route_path` = '/system/reset',
    `component`  = 'system/reset',
    `perm_code`  = 'system:reset'
WHERE `id` = 90;
