-- ============================================================================
-- V87 按钮级细权限补码（方案 B「按钮 code == 接口 code」+ 方案 X「粗 code 补细 code」）
-- ============================================================================
--
-- 【背景：为什么必须补】
--   1) ReturnController.complete / reject 从「类级 @RequirePerm("return:manage")」
--      拆为方法级细 code（return:approve / return:reject）。
--      按 PermissionInterceptor 规则「方法级注解覆盖类级（非 AND）」，拆分后
--      仅持有 return:manage 的老角色调用这两个接口会直接 403 → 必须补细 code。
--   2) 前端附件上传/下载按钮由各模块私有 code（invoice:upload 等）改挂通用
--      file:upload / file:download —— 对应 FileController 上已存在的同名接口权限。
--      持有旧私有 code 的老角色若没有 file:* → 改版后按钮会消失 → 补齐 file:*。
--
-- 【为什么不需要补 help:toggle】
--   前端「启用/停用」按钮 code 本就是 help:toggle（V81 建），改造后新增的
--   PUT /system/help/{id}/toggle 接口也用 help:toggle，两侧本就一致：
--   原本「有 help:toggle 但无 help:edit」点按钮会 403 的老问题反而被修复。
--   持有 help:edit 的角色仍可通过编辑弹窗改 status（update 接口未动），能力无损。
--   故此处不补，避免无谓扩大权限。
--
-- 【幂等性】
--   三段均走 LEFT JOIN ... IS NULL 去重（不用 NOT EXISTS 子查询，避免 MySQL
--   在 INSERT ... SELECT 中引用目标表报 1093）。sys_role_permission 上有唯一键
--   uk_role_permission(tenant_id, role_id, permission_id) 兜底。重复执行安全。
--   只补不删，不触碰任何已有数据。
-- ============================================================================

-- 1) 持有 return:manage 的角色 → 补 return:approve + return:reject（防拆分后 403）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT DISTINCT rp.role_id, p2.id
FROM `sys_role_permission` rp
         JOIN `sys_permission` p1
              ON p1.id = rp.permission_id AND p1.perm_code = 'return:manage'
         JOIN `sys_permission` p2
              ON p2.perm_code IN ('return:approve', 'return:reject')
         LEFT JOIN `sys_role_permission` x
              ON x.role_id = rp.role_id
                  AND x.permission_id = p2.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL;

-- 2) 持有各模块私有上传 code 的角色 → 补通用 file:upload（防按钮消失）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT DISTINCT rp.role_id, p2.id
FROM `sys_role_permission` rp
         JOIN `sys_permission` p1
              ON p1.id = rp.permission_id
                  AND p1.perm_code IN ('invoice:upload', 'receipt:upload', 'shipment:upload')
         JOIN `sys_permission` p2
              ON p2.perm_code = 'file:upload'
         LEFT JOIN `sys_role_permission` x
              ON x.role_id = rp.role_id
                  AND x.permission_id = p2.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL;

-- 3) 持有各模块私有下载 code 的角色 → 补通用 file:download（防按钮消失）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT DISTINCT rp.role_id, p2.id
FROM `sys_role_permission` rp
         JOIN `sys_permission` p1
              ON p1.id = rp.permission_id
                  AND p1.perm_code IN ('invoice:download', 'receipt:download')
         JOIN `sys_permission` p2
              ON p2.perm_code = 'file:download'
         LEFT JOIN `sys_role_permission` x
              ON x.role_id = rp.role_id
                  AND x.permission_id = p2.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL;
