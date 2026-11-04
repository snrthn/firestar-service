-- ============================================================================
-- V12：预置标准岗位种子数据（sys_post）
-- 背景：岗位管理模块（V10 建表、V11 补 ext 列）上线后，预置一批通用企业标准岗位，
--       便于「用户管理」创建用户时直接在下拉中选择，不必先手工建岗位。
-- 幂等：post_code 受 (tenant_id, post_code) 唯一键约束，INSERT ... ON DUPLICATE KEY
--       UPDATE 重复执行仅刷新名称/排序/状态/备注，不新增重复行；clean 全量重建亦安全。
-- 依赖：V10(建表) → V11(补 ext 列)
-- ============================================================================

INSERT INTO `sys_post` (`post_code`, `post_name`, `sort_no`, `status`, `remark`, `tenant_id`) VALUES
    ('GM',    '总经理',     1,  'ENABLED', '公司最高管理者',        0),
    ('VGM',   '副总经理',   2,  'ENABLED', '协助总经理分管业务',    0),
    ('DM',    '部门经理',   3,  'ENABLED', '部门负责人',            0),
    ('SUP',   '主管',       4,  'ENABLED', '业务主管',              0),
    ('STAFF', '专员',       5,  'ENABLED', '业务执行人员',          0),
    ('ENG',   '技术工程师', 6,  'ENABLED', '技术研发',              0),
    ('SALES', '销售代表',   7,  'ENABLED', '市场销售',              0),
    ('FIN',   '财务专员',   8,  'ENABLED', '财务核算',              0),
    ('HR',    '人事专员',   9,  'ENABLED', '人力资源',              0),
    ('OPS',   '运营专员',  10,  'ENABLED', '运营支持',              0),
    ('PURCH', '采购专员',  11,  'ENABLED', '物资采购',              0),
    ('QC',    '质检专员',  12,  'ENABLED', '质量检验',              0)
ON DUPLICATE KEY UPDATE
    `post_name` = VALUES(`post_name`),
    `sort_no`   = VALUES(`sort_no`),
    `status`    = VALUES(`status`),
    `remark`    = VALUES(`remark`);
