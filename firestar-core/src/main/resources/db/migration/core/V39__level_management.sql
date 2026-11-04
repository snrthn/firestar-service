-- ============================================================================
-- V39：职级管理（sys_level 表 + sys_user.level_code + 菜单/权限点）
-- 依赖：V10（sys_post 同构范式）、V1（sys_user 已建）、V36（菜单 id 用到 90）
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE；菜单 sort_no 级联为 UPDATE（可重复执行）
-- 决策：C 拍板延续——不授 ROLE_ADMIN，权限点与菜单由 RBAC 手动分配。
-- 背景：P3 Step2 的「职级」节点类型（LEVEL）此前只有空壳配置入口，
--       本迁移补齐数据层：职级定义（sys_level）+ 用户绑定（sys_user.level_code）。
--       LevelResolver bean 与待办双查仍留 Step 3（见 docs/P3-审批工作流-实施方案.md）。
-- ============================================================================

-- ========== 1. sys_level 表（完全对齐 sys_post DDL 范式） ==========
-- 与岗位的区别：岗位答「干什么活」，职级答「哪个审批层级」——两个维度，不复用 sys_post。
-- level_no 为数值层级（1=最低，逐级+1），供「高一级」语义比较（dict 排序字段不承载比较语义）。
-- level_code 创建后不可改（同 username / roleCode / postCode 纪律）。
CREATE TABLE IF NOT EXISTS `sys_level` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  `level_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '职级编码（创建后不可改）',
  `level_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '职级名称',
  `level_no` int NOT NULL DEFAULT '0' COMMENT '层级数值（1=最低，逐级+1；供「高一级」比较）',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '显示排序',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户 ID',
  `created_by` bigint DEFAULT NULL COMMENT '创建人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint DEFAULT NULL COMMENT '更新人 ID',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `ext` json DEFAULT NULL COMMENT '扩展字段（禁放金额/状态/检索字段）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_tenant_level_code` (`tenant_id`,`level_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='职级';

-- ========== 2. sys_user 加 level_code 列（V14 加 email / V28 加 avatar_url 同款范式） ==========
-- 存 level_code 而非 level_id：节点配置里填的就是编码（如 L3），解析时一步直达；
-- 前提是 level_code 创建后不可改（唯一键约束 + Service 校验双重保证）。
ALTER TABLE `sys_user`
  ADD COLUMN `level_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职级编码 → sys_level.level_code（可空，非必填）' AFTER `post_id`;

-- ========== 3. 权限点 2 个（action_type 同 V25/V38：READ/WRITE） ==========
INSERT INTO `sys_permission` (`perm_code`, `perm_name`, `resource_type`, `action_type`, `status`, `tenant_id`) VALUES
('level:view',   '职级管理查看', 'API', 'READ',  'ENABLED', 0),
('level:manage', '职级管理管理', 'API', 'WRITE', 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `perm_name` = VALUES(`perm_name`), `resource_type` = VALUES(`resource_type`), `action_type` = VALUES(`action_type`), `status` = VALUES(`status`);

-- ========== 4. 菜单：「职级管理」插入系统管理，位于岗位管理(5) 与 菜单管理(6) 之间 ==========
-- id 93 已查库确认未占用（系统管理子菜单最大 id=36，一级菜单最大 id=91/92）。
-- 位次实现：菜单管理起 sort_no 全部 +1，职级管理占 sort_no=6。
UPDATE `sys_menu` SET `sort_no` = `sort_no` + 1 WHERE `parent_id` = 12 AND `sort_no` >= 6;

INSERT INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `route_path`, `component`, `perm_code`, `menu_type`, `sort_no`, `visible`, `status`, `icon`, `tenant_id`) VALUES
(93, '职级管理', 12, '/system/level', 'system/level', 'level:view', 'MENU', 6, 1, 'ENABLED', 'Histogram', 0)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `route_path` = VALUES(`route_path`), `component` = VALUES(`component`), `perm_code` = VALUES(`perm_code`), `menu_type` = VALUES(`menu_type`), `sort_no` = VALUES(`sort_no`), `visible` = VALUES(`visible`), `status` = VALUES(`status`), `icon` = VALUES(`icon`);

-- ========== 5. 角色授权：C 拍板「不授 ROLE_ADMIN」 ==========
-- 权限点 level:view / level:manage 与菜单 93 均交由 RBAC 手动分配，本段刻意省略。
