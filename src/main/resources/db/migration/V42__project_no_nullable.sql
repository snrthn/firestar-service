-- V42: fin_project.project_no 改为可空
--
-- 背景（Step 3 立项链路实锤）：需求文档规定「项目立项审批通过后，才赋予项目编号」，
-- 即提交审批时 project_no 应为 NULL。但 V1 基线把该列建成了 NOT NULL 无默认值，
-- MP insert 不带该列 → MySQL 直接拒绝（Field 'project_no' doesn't have a default value）。
-- 本迁移把过度约束修正为与业务规则一致：审批通过前可空，通过时回填正式编号。
--
-- 注意：同步 DatabaseCheckServiceTest 的 Flyway 版本期望（40→41，表数不变）。

ALTER TABLE fin_project
    MODIFY COLUMN project_no varchar(64) NULL DEFAULT NULL
    COMMENT '项目编号（审批通过后赋予，此前为空）';
