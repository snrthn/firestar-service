-- V92：清理「按钮被授权、但其父菜单未被授权」的孤儿行
--
-- 背景（2026-10-31 事故）：
--   assignMenus 曾加过一层「视野外按钮保护」——把「父菜单不在提交集」的 BUTTON 节点判定为
--   「管理员在树上看不到」并保留原授权不删。该逻辑语义相反：父菜单没提交 == 管理员明确取消了
--   整个模块，此时保留子按钮是错的。真实后果：取消「文件管理」模块保存后重新打开，
--   文件上传/下载/别名/删除（364-367）被悄悄保留并重新勾上，与保存时渲染不一致。
--
-- 本迁移做两件事：
--   1) 清掉这类孤儿行（幂等，可重复执行）——保证存量数据与「所见即所得」口径一致；
--      撤销保护后若不清理，已被污染的 364-367 行会一直留在库里。
--   2) 无新增/删除 sys_permission 种子，不影响 DatabaseCheckService 的任何计数基线。
--
-- 语义说明：父页面都进不去，其下按钮授权没有任何意义（严格分离下按钮也不会渲染），
--   故删除是安全的；V88 的通用回填只在迁移时跑一次，不会与本迁移打架。

-- 1) 删除：授权了 BUTTON 节点，但同一角色 + 同租户未授权其直接父菜单
DELETE rm
FROM sys_role_menu rm
         JOIN sys_menu m ON m.id = rm.menu_id
         LEFT JOIN sys_role_menu pm
                   ON pm.tenant_id <=> rm.tenant_id   -- <=> NULL 安全等值，避免 tenant_id 为 NULL 时误判为孤儿
                       AND pm.role_id = rm.role_id
                       AND pm.menu_id = m.parent_id
WHERE m.menu_type = 'BUTTON'
  AND m.parent_id IS NOT NULL
  AND m.parent_id <> 0
  AND pm.id IS NULL;

-- 2) 自检（写入 flyway 日志便于核对，不影响结果）：若仍残留则为 0
-- SELECT COUNT(*) AS remain_orphan_button_rows
-- FROM sys_role_menu rm
--          JOIN sys_menu m ON m.id = rm.menu_id
--          LEFT JOIN sys_role_menu pm
--                    ON pm.tenant_id = rm.tenant_id
--                        AND pm.role_id = rm.role_id
--                        AND pm.menu_id = m.parent_id
-- WHERE m.menu_type = 'BUTTON'
--   AND m.parent_id IS NOT NULL
--   AND m.parent_id <> 0
--   AND pm.id IS NULL;
