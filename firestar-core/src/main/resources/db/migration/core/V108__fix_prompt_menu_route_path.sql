-- ============================================================================
-- V108：修复 V106 种下的数据缺陷 —— 菜单 9502「AI 提示词管理」route_path 漏了前导斜杠。
--
-- 现象：从任一非根路由（如 /system/model-config）点开「AI提示词管理」必现整页 403；
--       仅当从根路由（/dashboard）进入才正常。全库 system/* 菜单的 route_path
--       均带前导 "/"（/system/user、/system/model-config…），唯独 9502 是
--       "system/prompt-config"（缺 "/"）—— 这是 V106 种子 SQL 的笔误。
--
-- 根因链路：
--   侧栏 AppSidebar.vue 用 el-menu 的 router 模式，菜单项 index = route_path，
--   点击即用该字符串 router.push()。route_path 不以 "/" 开头时，vue-router 把它
--   当作【相对路径】解析：
--     · 当前在 /dashboard（根）→ 相对解析为 /system/prompt-config → 命中 → 正常
--       （正是"关掉所有页签再开"能用的原因）
--     · 当前已在任意 /system/* 路由 → 相对解析替换末段 → /system/system/prompt-config
--       → 无匹配 → 命中 catch-all 兜底 → 整页 403
--       （正是"先开别的页签再开它就 403"的原因）
--   只有 9502 中招，恰恰因为它是全库唯一 route_path 缺 "/" 的菜单。
--
-- 修复：仅把 route_path 补上前导 "/"（变绝对路径）。component 字段
--       "system/prompt-config" 是 import.meta.glob 的视图路径，本就不带斜杠，保持不动。
--       syncDynamicRoutes 内部本就会 strip 前导斜杠再注册，故注册出的路由 path 不变，
--       仅修正「侧栏点击跳转」用的源值即可。
--
-- 幂等：仅在当前值仍为错误值 'system/prompt-config' 时更新；已修正则 no-op。
-- 不改变表结构，EXPECTED_TABLE_COUNT 不变。
-- ============================================================================

UPDATE `sys_menu`
SET `route_path` = '/system/prompt-config'
WHERE `id` = 9502 AND `route_path` = 'system/prompt-config';
