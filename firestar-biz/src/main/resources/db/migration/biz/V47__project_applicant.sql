-- V47: 立项「我发起的」支持——applicant_username 列 + 历史数据回填
--
-- 背景：审批中心「我发起的」Tab 需要按发起人过滤。两条备选来源都不可靠：
--   1) fin_project.created_by 审计列：AuditMetaObjectHandler 明确注释「登录模块尚未实现，
--      暂不填」（createdBy/updatedBy 一个字节都没写过），恒为 NULL，不能用；
--   2) ACT_HI_PROCINST.START_USER_ID_：Flowable 只在 IdentityService.authenticatedUserId
--      有值时落库，本项目此前从未 set，历史实例同样是 NULL（本次引擎层补传，只治新数据）。
-- 唯一切实的历史来源：submit 时写入的 applicantUsername 流程变量
-- （ACT_HI_VARINST.NAME_ = 'applicantUsername'，第一版 submit 起就有值）。
--
-- 做法：业务列 applicant_username 记录发起人，create/submit 双写点保新数据，
-- 本迁移从流程变量回填历史（幂等：仅填 NULL 行）。
--
-- ⚠️ 2026-10-22 事故修复记录（必须保留）：
--   第一版 V47 直接写 `v.BUSINESS_KEY_`，但 ACT_HI_VARINST 根本没有 BUSINESS_KEY_ 列
--   （V3 建表脚本实锤：只有 ID_/PROC_INST_ID_/NAME_/TEXT_...），business_key 在
--   ACT_HI_PROCINST。首次启动 ALTER 成功（MySQL DDL 隐式提交，Flyway 事务回滚不掉）
--   但 UPDATE 报 Error 1054 Unknown column，迁移记 failed，之后每次启动 validate 都拒。
--   恢复步骤：DELETE FROM flyway_schema_history WHERE version = '47' AND success = 0; 再重启。
--   因此本版 DDL 全部条件化（information_schema 判断），兼容「列/索引已存在」的半迁移状态。

-- 幂等加列（列可能已被第一版迁移的 ALTER 建出）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_project' AND COLUMN_NAME = 'applicant_username');
SET @ddl_col = IF(@has_col = 0,
    'ALTER TABLE fin_project ADD COLUMN applicant_username VARCHAR(64) NULL COMMENT ''审批发起人用户名（审批中心「我发起的」过滤；create/submit 双写）'' AFTER sales_manager_id',
    'DO 0');
PREPARE s1 FROM @ddl_col;
EXECUTE s1;
DEALLOCATE PREPARE s1;

-- 幂等建索引
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_project' AND INDEX_NAME = 'idx_fin_project_applicant');
SET @ddl_idx = IF(@has_idx = 0,
    'CREATE INDEX idx_fin_project_applicant ON fin_project (applicant_username)',
    'DO 0');
PREPARE s2 FROM @ddl_idx;
EXECUTE s2;
DEALLOCATE PREPARE s2;

-- 历史回填：ACT_HI_VARINST 无 BUSINESS_KEY_，按 PROC_INST_ID_ JOIN ACT_HI_PROCINST 取
-- business_key（= 项目 id 的字符串形式，submit 时 String.valueOf(projectId) 传入）；
-- applicantUsername 为 string 变量，值在 TEXT_ 列
-- ⚠️ 2026-10-22 生产事故修复（第二次）：表名必须大写！生产 MySQL 跑 Linux，
--   lower_case_table_names=0 大小写敏感，小写 act_hi_varinst 报 ERROR 1146
--   （本地 Windows MySQL 大小写不敏感所以永远测不出）。与 V3 建表脚本命名对齐。
UPDATE fin_project p
    JOIN (
        SELECT CAST(pi.BUSINESS_KEY_ AS SIGNED) AS biz_id, MAX(v.TEXT_) AS applicant
        FROM ACT_HI_VARINST v
        JOIN ACT_HI_PROCINST pi ON v.PROC_INST_ID_ = pi.PROC_INST_ID_
        WHERE v.NAME_ = 'applicantUsername'
          AND pi.BUSINESS_KEY_ IS NOT NULL
        GROUP BY pi.BUSINESS_KEY_
    ) t ON p.id = t.biz_id
SET p.applicant_username = t.applicant
WHERE p.applicant_username IS NULL;
