-- ============================================================================
-- V10：岗位管理模块 + 数据修正（sys_role.status 归一 / 用户补手机号与岗位列）
-- 背景：
--   ① 用户/角色/权限三模块已就绪，现补「岗位管理」模块；
--   ② V2 种子把 sys_role.status 写成中文'启用'/'停用'，与全库 ENABLE/DISABLED
--      约定不符，导致前端列表全判「停用」、表单 radio 匹配不上；
--   ③ 用户新增需手机号、可选岗位（创建用户时非必填）。
-- 内容：
--   ① sys_role.status 中文归一（'启用'→ENABLED / '停用'→DISABLED），幂等 UPDATE；
--   ② sys_user 增加 phone / post_id 两列（均 NULL，兼容现有 admin 与历史数据）；
--   ③ 新建 sys_post 岗位表；
--   ④ 岗位权限点 rbac:post + 菜单(id=24, 系统管理父下) + ROLE_ADMIN 授权。
-- 幂等说明：版本迁移由 Flyway 保证只执行一次；clean 全量重建时 baseline 不含
--   上述列，ALTER 仍成功。UPDATE/INSERT 均带幂等保护，重复执行结果一致。
-- 依赖：V1(baseline) → V4(rbac 种子) → V8(org) → V9(org 模板)
-- ============================================================================

-- 1. sys_role.status 归一（仅修正 V2 笔误，不动 sys_dict_* 等其它表）
UPDATE `sys_role` SET `status` = 'ENABLED'  WHERE `status` = '启用';
UPDATE `sys_role` SET `status` = 'DISABLED' WHERE `status` = '停用';

-- 2. sys_user 补充手机号与岗位列（nullable，不破坏现有数据）
ALTER TABLE `sys_user`
    ADD COLUMN `phone`   VARCHAR(20) NULL COMMENT '手机号'        AFTER `last_login_ip`,
    ADD COLUMN `post_id` BIGINT      NULL COMMENT '岗位 ID → sys_post.id（单岗，非必填）' AFTER `phone`;

-- 3. 岗位表
CREATE TABLE IF NOT EXISTS `sys_post` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `post_code`  VARCHAR(64)  NOT NULL COMMENT '岗位编码',
    `post_name`  VARCHAR(64)  NOT NULL COMMENT '岗位名称',
    `sort_no`    INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `remark`     VARCHAR(255) NULL COMMENT '备注',
    `tenant_id`  BIGINT       NOT NULL DEFAULT 0 COMMENT '租户 ID',
    `created_by` BIGINT       NULL COMMENT '创建人 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by` BIGINT       NULL COMMENT '更新人 ID',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_post_code` (`tenant_id`, `post_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='岗位';

-- 4. 岗位权限点（模块级 WRITE，同 org:manage 惯例）
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('rbac:post', '岗位管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- 5. 岗位管理菜单（系统管理 id=12 下，sort_no=8 排最后，不动现有 1–7 排序）
INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `tenant_id`) VALUES
(24, '岗位管理', 12, '/system/post', 'system/post', 'rbac:post', 'MENU', 8, 1, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`);

-- 6. ROLE_ADMIN 授权（权限点 + 菜单），与 V4/V8 惯例一致
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT r.id, p.id, 'ENABLED', 0
FROM `sys_role` r, `sys_permission` p
WHERE r.role_code = 'ROLE_ADMIN' AND p.perm_code = 'rbac:post'
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT r.id, m.id, 0
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code = 'ROLE_ADMIN' AND m.id = 24
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);
