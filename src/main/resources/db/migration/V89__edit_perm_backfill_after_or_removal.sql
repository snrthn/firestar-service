-- ============================================================================
-- V89 编辑权限补码（配套「@RequirePerm 多值 OR 收敛为单一 code」）
-- ============================================================================
--
-- 【背景：为什么必须补】
--   PermissionInterceptor:98 的判定是 OR：
--       Arrays.stream(required).anyMatch(owned::contains)
--   历史上各模块 update 接口普遍写成
--       @RequirePerm({"X:edit", "X:create"})   ← 两个 code 互为兜底
--   后果（虎哥 2026-10-30 实测）：在权限页取消 X:edit，只要角色还持有 X:create，
--   编辑接口照样放行 —— 与「一个按钮/接口对应一个 code」的原则相悖，权限页行为不直观。
--
--   本轮收敛：15 处 update 接口改为单一 code（X:edit），去掉 X:create 兜底。
--   ProcessDefController 例外：其 create 方法本来就挂 process:create（未改动），
--   本轮只把 update 的 {"process:edit","process:config"} 收敛为 process:edit。
--
--   ⚠️ 直接改会让「只持有 X:create、没有 X:edit」的角色点编辑立刻 403，
--      故必须先把这部分角色的 X:edit 补上，再让新注解生效。
--
-- 【补码规则】
--   角色持有 src 码 且 不持有 dst 码 → INSERT dst 码。只补不删。
--
-- 【幂等性】
--   LEFT JOIN 目标表用 IS NULL 排除已存在行（不用 NOT EXISTS 子查询，规避 MySQL 1093：
--   INSERT ... SELECT 的目标表不能直接出现在子查询 FROM 里）。
--   sys_role_permission 唯一键 uk_role_permission(tenant_id, role_id, permission_id) 兜底。
-- ============================================================================

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT DISTINCT rp.role_id, pdst.id, 'ENABLED', rp.tenant_id
FROM `sys_role_permission` rp
         JOIN `sys_permission` psrc
              ON psrc.id = rp.permission_id
         JOIN (
    --              原本持此码即能编辑 → 现在必须补上目标码
    SELECT 'user:create'       AS src, 'user:edit'       AS dst
    UNION ALL SELECT 'role:create',       'role:edit'
    UNION ALL SELECT 'menu:create',       'menu:edit'
    UNION ALL SELECT 'permission:create', 'permission:edit'
    UNION ALL SELECT 'org:create',        'org:edit'
    UNION ALL SELECT 'post:create',       'post:edit'
    UNION ALL SELECT 'level:create',      'level:edit'
    UNION ALL SELECT 'dict:create',       'dict:edit'
    UNION ALL SELECT 'dict:data:create',  'dict:data:edit'
    UNION ALL SELECT 'project:create',    'project:edit'
    UNION ALL SELECT 'invoice:create',    'invoice:edit'
    UNION ALL SELECT 'receipt:create',    'receipt:edit'
    UNION ALL SELECT 'shipment:create',   'shipment:edit'
    UNION ALL SELECT 'help:create',       'help:edit'
    UNION ALL SELECT 'process:config',    'process:edit'
) m
              ON m.src = psrc.perm_code
         JOIN `sys_permission` pdst
              ON pdst.perm_code = m.dst
         LEFT JOIN `sys_role_permission` x
              ON x.role_id = rp.role_id
                  AND x.permission_id = pdst.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL
ON DUPLICATE KEY UPDATE `status` = `sys_role_permission`.`status`;
