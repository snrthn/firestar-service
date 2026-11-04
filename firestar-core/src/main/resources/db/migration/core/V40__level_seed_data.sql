-- ============================================================================
-- V40：预置通用职级种子数据（sys_level）
-- 背景：职级管理模块（V39 建表）上线后，与 V12 预置标准岗位同款思路，预置一套
--       通用企业职级阶梯（L1 助理 → L9 副总裁），便于「用户管理」绑定职级、
--       「流程节点配置」选择职级时直接下拉选取，不必先手工建职级。
-- 语义：level_no 为数值层级（1=最低，逐级+1），供 Step 3「高一级」审批语义比较；
--       sort_no 与 level_no 同序（同级内数字越小越靠前，留出后续插半级的空位）。
-- 幂等：level_code 受 (tenant_id, level_code) 唯一键约束，INSERT ... ON DUPLICATE KEY
--       UPDATE 重复执行仅刷新名称/层级/排序/状态/备注，不新增重复行；clean 全量重建亦安全。
-- 依赖：V39（sys_level 已建）
-- ============================================================================

INSERT INTO `sys_level` (`level_code`, `level_name`, `level_no`, `sort_no`, `status`, `remark`, `tenant_id`) VALUES
    ('L1', '助理',     1, 1, 'ENABLED', '基层执行，熟悉业务流程',        0),
    ('L2', '专员',     2, 2, 'ENABLED', '独立完成本职模块工作',          0),
    ('L3', '高级专员', 3, 3, 'ENABLED', '模块骨干，可指导新人',          0),
    ('L4', '资深专员', 4, 4, 'ENABLED', '跨模块攻坚，专业权威',          0),
    ('L5', '主管',     5, 5, 'ENABLED', '基层管理，带小组执行',          0),
    ('L6', '经理',     6, 6, 'ENABLED', '中层管理，统筹部门业务',        0),
    ('L7', '高级经理', 7, 7, 'ENABLED', '分管多条线，参与经营决策',      0),
    ('L8', '总监',     8, 8, 'ENABLED', '高层管理，对业务结果负责',      0),
    ('L9', '副总裁',   9, 9, 'ENABLED', '决策层，分管公司级业务',        0)
ON DUPLICATE KEY UPDATE
    `level_name` = VALUES(`level_name`),
    `level_no`   = VALUES(`level_no`),
    `sort_no`    = VALUES(`sort_no`),
    `status`     = VALUES(`status`),
    `remark`     = VALUES(`remark`);
