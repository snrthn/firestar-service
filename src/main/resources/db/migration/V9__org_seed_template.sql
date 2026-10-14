-- ============================================================================
-- V9：标准组织架构模板种子数据（两级企业架构，14 个部门）
-- 背景：V8 落了 org 模块骨架但未种数据（原定由使用方在页面添加）。
--       虎哥要求直接上一版标准模板：即拿即用的常见公司架构，
--       负责人留空（leader_id=NULL），由使用方在组织架构页逐项指定。
-- 结构（呼应 RBAC 互斥角色与财务业务流程：销售/财务分设，财务拆会计+出纳）：
--   总部(100)
--   ├─ 总经办(101)
--   ├─ 研发中心(102)
--   │  ├─ 产品部(110) └─ 技术部(111)
--   ├─ 销售中心(103)
--   │  ├─ 销售一部(112) ├─ 销售二部(113) └─ 销售三部(114)
--   ├─ 财务部(104)
--   │  ├─ 会计组(115) └─ 出纳组(116)
--   ├─ 人力资源部(105)
--   └─ 运营部(106)
-- id 从 100 起（高位段）：避开页面上手工添加部门的低位自增 id，防 ON DUPLICATE
--   误覆盖用户数据；同一父级下 sort_no 从 1 连续编号。
-- 幂等：INSERT 带 ON DUPLICATE KEY UPDATE，重复执行收敛到同一状态。
-- 依赖：V1(baseline 建 sys_org) → V8(org 管理菜单/权限)
-- ============================================================================

INSERT INTO `sys_org` (`id`, `org_name`, `parent_id`, `leader_id`, `sort_no`, `status`, `tenant_id`) VALUES
(100, '总部',       0,   NULL, 1, 'ENABLED', 0),
(101, '总经办',     100, NULL, 1, 'ENABLED', 0),
(102, '研发中心',   100, NULL, 2, 'ENABLED', 0),
(110, '产品部',     102, NULL, 1, 'ENABLED', 0),
(111, '技术部',     102, NULL, 2, 'ENABLED', 0),
(103, '销售中心',   100, NULL, 3, 'ENABLED', 0),
(112, '销售一部',   103, NULL, 1, 'ENABLED', 0),
(113, '销售二部',   103, NULL, 2, 'ENABLED', 0),
(114, '销售三部',   103, NULL, 3, 'ENABLED', 0),
(104, '财务部',     100, NULL, 4, 'ENABLED', 0),
(115, '会计组',     104, NULL, 1, 'ENABLED', 0),
(116, '出纳组',     104, NULL, 2, 'ENABLED', 0),
(105, '人力资源部', 100, NULL, 5, 'ENABLED', 0),
(106, '运营部',     100, NULL, 6, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE
  `org_name` = VALUES(`org_name`),
  `parent_id` = VALUES(`parent_id`),
  `leader_id` = VALUES(`leader_id`),
  `sort_no` = VALUES(`sort_no`),
  `status` = VALUES(`status`);
