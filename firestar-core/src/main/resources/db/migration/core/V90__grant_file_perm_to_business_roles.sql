-- ============================================================================
-- V90 附件权限补齐：给业务角色授予 file:upload / file:download
-- ============================================================================
--
-- 【症状】
--   发货详情「标记客户收货」子弹窗里，橙色提示条与货物明细都在，
--   唯独「上传签收证据」按钮不渲染（v-has-perm="'file:upload'" 把它摘掉了）。
--
-- 【根因：不是前端渲染 bug，是授权数据没给到】
--   1) 附件按钮的 gate 口径 = 通用 file:*（虎哥 2026-10-30 拍板：按钮 code 必须等于
--      接口 code）。所有业务模块的上传/下载都走通用 FileController，其注解是
--      @RequirePerm("file:upload") / ("file:download")，按钮也就必须 gate 在这两个 code 上。
--   2) 而 V81 的授权规则是「前缀匹配」：file:upload 只给了 ROLE_ADMIN，
--      以及「已持有 file:* 前缀权限」的角色（V81 语句 3 / 语句 4）。
--      → 业务模块角色（持有 shipment:*/invoice:*/receipt:* 等，但没有任何 file:* 权限）
--        永远拿不到 file:upload。
--   3) 后果有两层，且必须一起解决：
--      · 按钮层：v-has-perm 判定不通过 → 控件不渲染（本次现象）；
--      · 接口层：即便把按钮硬放开，POST /files/upload 仍要求 file:upload → 403。
--      所以正确修法是【补授权】，不是改前端 gate（改 gate 只会变成「显示但点了 403」）。
--
-- 【修复范围】
--   凡持有任一业务模块动作码的角色，授予 file:upload + file:download：
--     · 业务模块：project / shipment / invoice / receipt / receivable / return /
--                 writeoff / agreement
--   file:alias / file:delete 属文件管理页自身的维护动作，不给业务角色（避免越权删改文件）。
--
-- 【严格分离下的第二半（必须做）】
--   方案 B-③ 严格分离后：按钮展示只认【菜单权限】sys_role_menu → sys_menu(BUTTON)，
--   接口放行只认【操作权限】sys_role_permission。
--   → 只插 sys_role_permission 按钮仍不会显示，故本迁移第 2 段同步补勾
--     file:upload / file:download 对应的菜单 BUTTON 节点（V81 建的 id 364 / 365）。
--
-- 【幂等性】
--   LEFT JOIN ... IS NULL 去重，可重复执行；两段各有唯一键兜底
--   （uk_tenant_perm_code / uk_role_menu）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 操作权限：给业务角色补 file:upload / file:download（接口放行用）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `status`, `tenant_id`)
SELECT DISTINCT x.role_id, p.id, 'ENABLED', x.tenant_id
FROM (
    -- 先物化成派生表：持有任一业务模块动作码的角色（规避 MySQL 1093）
    SELECT DISTINCT rp.role_id, rp.tenant_id
    FROM `sys_role_permission` rp
             JOIN `sys_permission` pm ON pm.id = rp.permission_id
    WHERE SUBSTRING_INDEX(pm.perm_code, ':', 1) IN
          ('project', 'shipment', 'invoice', 'receipt', 'receivable', 'return', 'writeoff', 'agreement')
) x
         JOIN `sys_permission` p
              ON p.perm_code IN ('file:upload', 'file:download')
         LEFT JOIN `sys_role_permission` y
              ON y.role_id = x.role_id
                  AND y.permission_id = p.id
                  AND y.tenant_id = x.tenant_id
WHERE y.id IS NULL
ON DUPLICATE KEY UPDATE `status` = 'ENABLED';

-- ---------------------------------------------------------------------------
-- 2. 菜单权限：补勾对应 BUTTON 节点（严格分离后按钮展示只认这条线）
--    节点由 perm_code 定位（V81 建的「文件上传」364 / 「文件下载」365，父菜单 system/file）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT DISTINCT rp.role_id, m.id, rp.tenant_id
FROM `sys_role_permission` rp
         JOIN `sys_permission` p
              ON p.id = rp.permission_id
                  AND p.perm_code IN ('file:upload', 'file:download')
         JOIN `sys_menu` m
              ON m.perm_code = p.perm_code
                  AND m.menu_type = 'BUTTON'
         LEFT JOIN `sys_role_menu` x
              ON x.role_id = rp.role_id
                  AND x.menu_id = m.id
                  AND x.tenant_id = rp.tenant_id
WHERE x.id IS NULL
ON DUPLICATE KEY UPDATE `tenant_id` = `sys_role_menu`.`tenant_id`;
