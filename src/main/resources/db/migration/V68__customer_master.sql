-- ============================================================================
-- V68：客户主表（运营中心数据看板 L0 地基）
-- 依赖：V1（fin_project 表及 customer_name 列已建）
--       V2（common_status 字典 ENABLED/DISABLED 已种）
--
-- 背景：虎哥 2026-10-26 拍板四项之一「引入客户主表」。
--       此前 fin_project.customer_name 是**自由文本**（V1 注释明写「不拆客户表，见方案 §1.3」），
--       导致数据看板的「客户维度」只能按字符串精确聚合 —— 重名/别名/改名都会裂成两个主体，
--       客户维度的应收/回款/账龄排名长期不可靠。本迁移为其建立唯一事实源。
--
-- 一期范围（虎哥 2026-10-26 拍板）：
--   ① 只做「主表 + 回填 + 项目关联下拉」，**不做**独立 CRUD 页面 / 菜单 / 权限点；
--   ② fin_project.customer_name **保留为快照冗余**（不移除、不弃用）：
--      项目单据上的客户名是「当时叫什么」的历史事实，客户主表改名不应回过头篡改历史单据；
--      看板统计按 customer_id 聚合，列表/详情展示仍可读 customer_name（免联表）；
--   ③ 客户编号沿用项目号的近似格式：项目号 PJ-{yyyyMMdd}-{id 四位补齐}，
--      故客户号 CUS-{yyyyMMdd}-{id 四位补齐}。
--
-- ⚠️ DatabaseCheckService 同步：EXPECTED_TABLE_COUNT 39→40（fin_ 前缀计入业务表）。
--    本迁移不新增 sys_dict_type / sys_dict_data 行（status 复用 V2 的 common_status），
--    故 dictTypeCount 33、dictDataCount 140 均保持不变。
--
-- 幂等：INSERT ... SELECT 用 NOT EXISTS 去重；UPDATE 带 (customer_id 未关联) 条件；
--      ALTER TABLE 用 IF NOT EXISTS 保护的列存在性判断（MySQL 无原生 ADD COLUMN IF NOT
--      EXISTS，这里靠 information_schema 查询 + 动态准备语句实现，可重复执行）。
-- ============================================================================

-- ========== 1. fin_customer 客户主表 ==========
-- 严格同构 V1 的 fin_item / V67 的 fin_return_line：统一审计列 + version 乐观锁 +
-- ext(json) 扩展 + 逻辑删除三件套。
CREATE TABLE IF NOT EXISTS `fin_customer` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `customer_no`    VARCHAR(32)   NULL COMMENT '客户编号（CUS-{yyyyMMdd}-{id 四位补齐}，与项目号 PJ- 同范式；回填后生成）',
    `customer_name`  VARCHAR(128)  NOT NULL COMMENT '客户名称',
    `contact_person` VARCHAR(64)   NULL COMMENT '联系人',
    `contact_phone`  VARCHAR(32)   NULL COMMENT '联系电话',
    `address`        VARCHAR(255)  NULL COMMENT '地址',
    `tax_no`         VARCHAR(64)   NULL COMMENT '纳税人识别号',
    `bank_name`      VARCHAR(128)  NULL COMMENT '开户银行',
    `bank_account`   VARCHAR(64)   NULL COMMENT '银行账号',
    `status`         VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status：ENABLED/DISABLED）',
    `remark`         VARCHAR(255)  NULL COMMENT '备注',

    `tenant_id`      BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`     BIGINT      NULL COMMENT '创建人 ID',
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`     BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`            JSON        NULL COMMENT '扩展字段',
    `is_deleted`     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `deleted_at`     DATETIME    NULL COMMENT '删除时间',
    `deleted_by`     BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_customer_no`   (`tenant_id`, `is_deleted`, `customer_no`),
    UNIQUE KEY `uk_customer_name` (`tenant_id`, `is_deleted`, `customer_name`),
    KEY `idx_customer_name`       (`tenant_id`, `is_deleted`, `customer_name`),
    KEY `idx_customer_status`     (`tenant_id`, `is_deleted`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户主表（V68：看板客户维度唯一事实源）';

-- ========== 2. fin_project 挂 customer_id ==========
-- MySQL 无 ADD COLUMN IF NOT EXISTS，用 information_schema 判断 + 动态 SQL 保证幂等。
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_project' AND COLUMN_NAME = 'customer_id'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE `fin_project` ADD COLUMN `customer_id` BIGINT NULL DEFAULT 0 COMMENT ''客户 ID → fin_customer.id（0=未关联；V68 引入客户主表）'' AFTER `customer_name`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_project' AND INDEX_NAME = 'idx_project_customer'
);
SET @ddl2 := IF(@exists_idx = 0,
    'ALTER TABLE `fin_project` ADD KEY `idx_project_customer` (`tenant_id`, `is_deleted`, `customer_id`)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- ========== 3. 回填：从存量项目的客户名去重建档 ==========
-- tenant_id 一并带上：本库当前是单租户（全 0），但迁移写成 tenant 无关的形式，
-- 将来多租户时同名客户不会跨租户算串。
INSERT INTO `fin_customer`
    (`customer_no`, `customer_name`, `status`, `remark`, `tenant_id`)
SELECT
    NULL,
    t.customer_name,
    'ENABLED',
    'V68 回填：由存量 fin_project.customer_name 去重生成',
    t.tenant_id
FROM (
    SELECT DISTINCT p.tenant_id, p.customer_name
    FROM `fin_project` p
    WHERE p.is_deleted = 0
      AND p.customer_name IS NOT NULL
      AND TRIM(p.customer_name) <> ''
) t
WHERE NOT EXISTS (
    SELECT 1 FROM `fin_customer` c
    WHERE c.customer_name = t.customer_name AND c.tenant_id = t.tenant_id
);

-- ========== 4. 生成客户编号 CUS-{yyyyMMdd}-{id 四位补齐} ==========
-- 刻意放在 INSERT 之后用 UPDATE：编号里要带自增 id，而单行 INSERT 时 id 还不存在。
-- 与 ProjectServiceImpl.generateProjectNo 完全同构，只是前缀不同。
UPDATE `fin_customer`
SET `customer_no` = CONCAT('CUS-', DATE_FORMAT(`created_at`, '%Y%m%d'), '-', LPAD(`id`, 4, '0'))
WHERE `customer_no` IS NULL OR `customer_no` = '';

-- ========== 5. 回填：项目表勾连客户 id ==========
-- customer_name 保留不动（快照冗余，虎哥决策#2）；
-- 租户 + 名称双条件 JOIN，避免多租户下同名互相串。
UPDATE `fin_project` p
JOIN `fin_customer` c
  ON c.customer_name = p.customer_name AND c.tenant_id = p.tenant_id AND c.is_deleted = 0
SET p.customer_id = c.id
WHERE p.is_deleted = 0
  AND (p.customer_id IS NULL OR p.customer_id = 0)
  AND p.customer_name IS NOT NULL
  AND TRIM(p.customer_name) <> '';
