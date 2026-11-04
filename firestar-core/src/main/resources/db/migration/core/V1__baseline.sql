-- ============================================================================
-- V1.0 基线脚本 · 火星财务系统
-- ============================================================================
-- 依据：《火星财务系统业务模型》v6.0 建表方案 v1.2
-- 表数：25 张（fin_ 业务 9 + sys_ 系统 9 + sys_ RBAC 7）
--
-- 【纪律】
-- 1. 本脚本一经提交永不修改（Flyway checksum 校验），后续变更走 V{n}__ 增量脚本
-- 2. 全表统一 L1 预留字段：tenant_id / 审计四件套 / version / ext
-- 3. 金额一律 DECIMAL(18,2)，禁止 float/double
-- 4. 金额索引首列必带 is_deleted（MyBatis-Plus 逻辑删除会自动追加该条件）
-- 5. 不建外键约束，只建普通索引（理由见方案 §7.2）
-- 6. 单号生成规则暂不实现，字段已预留 VARCHAR(64)
-- ============================================================================

-- ============================================================================
-- 第一部分：业务主链 9 张（fin_）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. fin_project 项目 —— 核心实体，所有业务的载体
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_project` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `project_no`      VARCHAR(64)  NOT NULL COMMENT '项目编号（业务键，规则待定）',
    `project_name`    VARCHAR(128) NOT NULL COMMENT '项目名称',
    `customer_name`   VARCHAR(128) NULL COMMENT '客户名称（不拆客户表，见方案 §1.3）',
    `sales_manager_id` BIGINT      NULL COMMENT '销售经理ID → sys_user.id',
    `contract_status` VARCHAR(32)  NOT NULL DEFAULT 'UNSIGNED' COMMENT '合同签约状态（字典 project_contract_status）',
    `contract_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '合同总金额',
    `approval_status` VARCHAR(32)  NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '审批状态',
    `process_instance_id` VARCHAR(64) NULL COMMENT 'Flowable 流程实例 ID',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业），0=平台默认',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段（禁放金额/状态/检索字段）',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_project_no` (`tenant_id`, `project_no`),
    KEY `idx_project_sales_manager` (`tenant_id`, `is_deleted`, `sales_manager_id`),
    KEY `idx_project_contract_status` (`tenant_id`, `is_deleted`, `contract_status`),
    KEY `idx_project_approval_status` (`tenant_id`, `is_deleted`, `approval_status`),
    KEY `idx_project_process_instance` (`process_instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='项目';

-- ---------------------------------------------------------------------------
-- 2. fin_item 货物明细 —— 项目下的销售货物清单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_item` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `project_id`   BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `item_name`    VARCHAR(128)  NOT NULL COMMENT '货物名称',
    `spec_model`   VARCHAR(128)  NULL COMMENT '规格型号',
    `unit`         VARCHAR(32)   NULL COMMENT '单位',
    `quantity`     INT           NOT NULL DEFAULT 0 COMMENT '数量',
    `unit_price`   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '单价',
    `total_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '合计金额',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    KEY `idx_item_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_item_project_name` (`tenant_id`, `is_deleted`, `project_id`, `item_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='货物明细';

-- ---------------------------------------------------------------------------
-- 3. fin_shipment 发货记录 —— 1 发货 = 1 应收单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_shipment` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `project_id`       BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `batch_no`         VARCHAR(64)   NOT NULL COMMENT '批次号（规则 FH-项目编号-001）',
    `shipment_date`    DATETIME      NULL COMMENT '发货时间',
    `shipment_amount`  DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '发货金额',
    `received_amount`  DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已收金额',
    `returned_amount`  DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '退货金额',
    `shipment_status`  VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '发货状态（字典 shipment_status）',
    `receivable_id`    BIGINT        NULL COMMENT '关联应收单 ID（1:1 反向引用）',
    `applicant_id`     BIGINT        NULL COMMENT '申请人ID',
    `shipped_by_id`    BIGINT        NULL COMMENT '发货人 ID',
    `confirmed_by_id`  BIGINT        NULL COMMENT '确认人 ID',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_shipment_batch` (`tenant_id`, `batch_no`),
    UNIQUE KEY `uk_shipment_receivable` (`receivable_id`),
    KEY `idx_shipment_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_shipment_status` (`tenant_id`, `is_deleted`, `shipment_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发货记录';

-- ---------------------------------------------------------------------------
-- 4. fin_shipment_line 发货明细行 —— 发货按行管理收货数量
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_shipment_line` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `shipment_id`   BIGINT        NOT NULL COMMENT '发货 ID → fin_shipment.id',
    `item_id`       BIGINT        NOT NULL COMMENT '货物明细 ID → fin_item.id',
    `shipment_qty`  INT           NOT NULL DEFAULT 0 COMMENT '发货数量',
    `received_qty`  INT           NOT NULL DEFAULT 0 COMMENT '已收数量',
    `returned_qty`  INT           NOT NULL DEFAULT 0 COMMENT '退货数量',
    `line_status`   VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '行状态（字典 shipment_line_status）',
    `unit_price`    DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '单价',
    `line_amount`   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '行金额',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    KEY `idx_line_shipment` (`tenant_id`, `is_deleted`, `shipment_id`),
    KEY `idx_line_item` (`tenant_id`, `is_deleted`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发货明细行';

-- ---------------------------------------------------------------------------
-- 5. fin_return_order 退货记录 —— 三级审批后触发红字冲减
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_return_order` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `return_no`           VARCHAR(64)   NOT NULL COMMENT '退货单号（业务键）',
    `project_id`          BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `shipment_id`         BIGINT        NULL COMMENT '关联发货 ID → fin_shipment.id',
    `return_amount`       DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '退货金额',
    `return_reason`       VARCHAR(512)  NULL COMMENT '退货原因',
    `approval_status`     VARCHAR(32)   NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '审批状态（字典 return_approval_status）',
    `process_instance_id` VARCHAR(64)   NULL COMMENT 'Flowable 流程实例 ID',
    `execute_status`      VARCHAR(32)   NOT NULL DEFAULT 'NOT_EXECUTED' COMMENT '冲减执行状态（字典 return_execute_status）',
    `applicant_id`        BIGINT        NULL COMMENT '发起人 ID',
    `applied_at`          DATETIME      NULL COMMENT '发起时间',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是（不可物理删除）',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_return_no` (`tenant_id`, `return_no`),
    KEY `idx_return_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_return_shipment` (`tenant_id`, `is_deleted`, `shipment_id`),
    KEY `idx_return_approval` (`tenant_id`, `is_deleted`, `approval_status`),
    KEY `idx_return_execute` (`tenant_id`, `is_deleted`, `execute_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退货记录';

-- ---------------------------------------------------------------------------
-- 6. fin_receivable 应收单 ★业财桥梁，核心表
--    金额公式（应用层维护）：
--      未核销金额 = 应收金额 - 已核销金额
--      未开票金额 = 应收金额 - 已开票金额
--      已开票金额 = SUM(关联发票金额)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_receivable` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `receivable_no`          VARCHAR(64)   NOT NULL COMMENT '应收单号（业务键）',
    `project_id`             BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `shipment_id`            BIGINT        NULL COMMENT '关联发货 ID → fin_shipment.id（1:1）',
    `doc_direction`          VARCHAR(32)   NOT NULL DEFAULT 'BLUE' COMMENT '单据方向：蓝字/红字（字典 doc_direction）',
    `original_receivable_id` BIGINT        NULL COMMENT '关联原应收单 ID（红冲链，已拍板不改原单状态）',
    `return_id`              BIGINT        NULL COMMENT '关联退货 ID → fin_return_order.id',
    `receivable_amount`      DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '应收金额',
    `written_off_amount`     DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已核销金额',
    `unwritten_off_amount`   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '未核销金额',
    `invoiced_amount`        DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已开票金额',
    `uninvoiced_amount`      DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '未开票金额',
    `receivable_date`        DATE          NULL COMMENT '应收日期',
    `status`                 VARCHAR(32)   NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '状态（字典 receivable_status）',
    `source_type`            VARCHAR(32)   NOT NULL DEFAULT 'FROM_SHIPMENT' COMMENT '来源类型（字典 receivable_source）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（核销并发必需）',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是（一层，不可删只能红冲）',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_receivable_no` (`tenant_id`, `receivable_no`),
    UNIQUE KEY `uk_receivable_shipment` (`shipment_id`),
    KEY `idx_recv_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_recv_status` (`tenant_id`, `is_deleted`, `status`),
    KEY `idx_recv_date` (`tenant_id`, `is_deleted`, `receivable_date`),
    KEY `idx_recv_original` (`original_receivable_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应收单';

-- ---------------------------------------------------------------------------
-- 7. fin_receipt 收款单
--    🔴 红线：receipt_amount 必须由后端在审批通过时写入，禁止前端传入
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_receipt` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `receipt_no`           VARCHAR(64)   NOT NULL COMMENT '收款单号（业务键）',
    `project_id`           BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `doc_direction`        VARCHAR(32)   NOT NULL DEFAULT 'BLUE' COMMENT '单据方向：蓝字/红字（字典 doc_direction）',
    `original_receipt_id`  BIGINT        NULL COMMENT '关联原收款单 ID（红冲链）',
    `return_id`            BIGINT        NULL COMMENT '关联退货 ID',
    `receipt_amount`       DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '收款金额（后端审批通过时写入）',
    `written_off_amount`   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已核销金额',
    `unwritten_off_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '未核销金额',
    `receipt_date`         DATE          NULL COMMENT '收款日期',
    `bank_receipt_path`    VARCHAR(512)  NULL COMMENT '银行回单路径（只存路径，不存文件）',
    `approval_status`      VARCHAR(32)   NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '审批状态（字典 receipt_status）',
    `receipt_type`         VARCHAR(32)   NOT NULL DEFAULT 'NORMAL' COMMENT '款项类型（字典 receipt_type）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（出纳并发审批必需）',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是（一层，不可删只能红冲）',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_receipt_no` (`tenant_id`, `receipt_no`),
    KEY `idx_receipt_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_receipt_approval` (`tenant_id`, `is_deleted`, `approval_status`),
    KEY `idx_receipt_date` (`tenant_id`, `is_deleted`, `receipt_date`),
    KEY `idx_receipt_original` (`original_receipt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收款单';

-- ---------------------------------------------------------------------------
-- 8. fin_write_off 核销记录 —— 收款与应收的匹配明细
--    🔴 核销必须单事务：改收款已核销 + 改应收已核销 + 插本记录，三者同成同败
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_write_off` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `write_off_no`          VARCHAR(64)   NOT NULL COMMENT '核销单号（业务键）',
    `receivable_id`         BIGINT        NOT NULL COMMENT '应收单 ID → fin_receivable.id',
    `receipt_id`            BIGINT        NOT NULL COMMENT '收款单 ID → fin_receipt.id',
    `amount`                DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '本次核销金额',
    `write_off_date`        DATE          NULL COMMENT '核销日期',
    `write_off_method`      VARCHAR(32)   NOT NULL DEFAULT 'MANUAL' COMMENT '核销方式（字典 writeoff_method）',
    `status`                VARCHAR(32)   NOT NULL DEFAULT 'NORMAL' COMMENT '核销状态（字典 writeoff_status）',
    `original_write_off_id` BIGINT        NULL COMMENT '关联原核销 ID（反核销链）',
    `return_id`             BIGINT        NULL COMMENT '关联退货 ID',
    `operator_id`           BIGINT        NULL COMMENT '操作人 ID',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是（一层，不可删只能反核销）',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_write_off_no` (`tenant_id`, `write_off_no`),
    KEY `idx_write_off_receivable` (`tenant_id`, `is_deleted`, `receivable_id`),
    KEY `idx_write_off_receipt` (`tenant_id`, `is_deleted`, `receipt_id`),
    KEY `idx_write_off_status` (`tenant_id`, `is_deleted`, `status`),
    KEY `idx_write_off_date` (`tenant_id`, `is_deleted`, `write_off_date`),
    KEY `idx_write_off_original` (`original_write_off_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='核销记录';

-- ---------------------------------------------------------------------------
-- 9. fin_invoice 发票记录
--    ⚠️ 发票号码/代码/税率/税额为需求文档漏项，方案主动补齐
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_invoice` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `project_id`         BIGINT        NOT NULL COMMENT '项目 ID → fin_project.id',
    `receivable_id`      BIGINT        NOT NULL COMMENT '应收单 ID → fin_receivable.id',
    `doc_direction`      VARCHAR(32)   NOT NULL DEFAULT 'BLUE' COMMENT '单据方向：蓝字/红字（字典 doc_direction）',
    `original_invoice_id` BIGINT       NULL COMMENT '关联原发票 ID（红冲链）',
    `return_id`          BIGINT        NULL COMMENT '关联退货 ID',
    `invoice_no`         VARCHAR(64)   NULL COMMENT '发票号码（文档漏项，方案补）',
    `invoice_code`       VARCHAR(64)   NULL COMMENT '发票代码（文档漏项，方案补）',
    `invoice_amount`     DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '开票金额',
    `tax_rate`           DECIMAL(5,4)  NULL COMMENT '税率（文档漏项，方案补，如 0.1300=13%）',
    `tax_amount`         DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '税额（文档漏项，方案补）',
    `invoice_date`       DATE          NULL COMMENT '开票日期',
    `invoice_file_path`  VARCHAR(512)  NULL COMMENT '发票文件路径（只存路径）',
    `invoice_status`     VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '开票状态（字典 invoice_status）',
    `applicant_id`       BIGINT        NULL COMMENT '申请人 ID',
    `clerk_id`           BIGINT        NULL COMMENT '开票员 ID',
    `downloader_id`      BIGINT        NULL COMMENT '下载人 ID',
    `downloaded_at`      DATETIME      NULL COMMENT '下载时间',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',
    `is_deleted`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是（一层，不可删只能红冲）',
    `deleted_at`   DATETIME    NULL COMMENT '删除时间',
    `deleted_by`   BIGINT      NULL COMMENT '删除人 ID',

    PRIMARY KEY (`id`),
    KEY `idx_invoice_project` (`tenant_id`, `is_deleted`, `project_id`),
    KEY `idx_invoice_receivable` (`tenant_id`, `is_deleted`, `receivable_id`),
    KEY `idx_invoice_status` (`tenant_id`, `is_deleted`, `invoice_status`),
    KEY `idx_invoice_date` (`tenant_id`, `is_deleted`, `invoice_date`),
    KEY `idx_invoice_no` (`tenant_id`, `invoice_no`),
    KEY `idx_invoice_original` (`original_invoice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发票记录';

-- ============================================================================
-- 第二部分：系统表 9 张（sys_）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 10. sys_process_def 流程定义（动态生成 BPMN）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_process_def` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `process_code`            VARCHAR(64)  NOT NULL COMMENT '流程编码（业务键）',
    `process_name`            VARCHAR(128) NOT NULL COMMENT '流程名称',
    `flowable_deployment_id`  VARCHAR(64)  NULL COMMENT 'Flowable 部署 ID',
    `def_version`             INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    `status`                  VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `node_list`               JSON         NULL COMMENT '节点列表（节点名称/类型/审批人/顺序/可否驳回）',
    `remark`                  VARCHAR(512) NULL COMMENT '备注',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_process_code` (`tenant_id`, `process_code`),
    KEY `idx_process_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程定义';

-- ---------------------------------------------------------------------------
-- 11. sys_org 组织架构（自引用树，用于逐级审批解析）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_org` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `org_name`      VARCHAR(128) NOT NULL COMMENT '部门名称',
    `parent_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '上级部门 ID（自引用，0=根）',
    `leader_id`     BIGINT       NULL COMMENT '部门负责人 ID → sys_user.id',
    `sort_no`       INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status，三层停用代替删除）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_org_parent` (`tenant_id`, `parent_id`),
    KEY `idx_org_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='组织架构';

-- ---------------------------------------------------------------------------
-- 12. sys_user 用户
--     ⚠️ password / force_change_password 为需求文档漏项，方案主动补齐
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `username`              VARCHAR(64)  NOT NULL COMMENT '用户名/账号',
    `nickname`              VARCHAR(64)  NOT NULL COMMENT '姓名',
    `password`              VARCHAR(128) NOT NULL DEFAULT '' COMMENT '密码哈希（BCrypt）',
    `force_change_password` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否强制改密：0否 1是',
    `dept_id`               BIGINT       NULL COMMENT '所属部门 ID → sys_org.id',
    `parent_id`             BIGINT       NULL COMMENT '直属上级 ID → sys_user.id',
    `status`                VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `last_login_at`         DATETIME     NULL COMMENT '最后登录时间',
    `last_login_ip`         VARCHAR(64)  NULL COMMENT '最后登录 IP',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`),
    KEY `idx_user_dept` (`tenant_id`, `dept_id`),
    KEY `idx_user_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户';

-- ---------------------------------------------------------------------------
-- 13. sys_audit_log 审计日志（第五层：只增不删不改，故无逻辑删除三字段）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_audit_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `operator_id`    BIGINT       NULL COMMENT '操作人 ID',
    `operation_type` VARCHAR(32)  NOT NULL COMMENT '操作类型（字典 audit_operation）',
    `target_type`    VARCHAR(64)  NULL COMMENT '目标对象类型',
    `target_id`      BIGINT       NULL COMMENT '目标对象 ID',
    `before_value`   JSON         NULL COMMENT '改前值快照',
    `after_value`    JSON         NULL COMMENT '改后值快照',
    `operated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `ip_address`     VARCHAR(64)  NULL COMMENT 'IP 地址',
    `remark`         VARCHAR(512) NULL COMMENT '备注',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（此表实际不使用）',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_audit_operator` (`tenant_id`, `operated_at`),
    KEY `idx_audit_target` (`tenant_id`, `target_type`, `target_id`),
    KEY `idx_audit_time` (`tenant_id`, `operated_at`),
    KEY `idx_audit_op_type` (`tenant_id`, `operation_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审计日志（只增不删不改）';

-- ---------------------------------------------------------------------------
-- 14. sys_file_storage 文件存储
--     🔴 红线：只存路径，文件实体存磁盘/对象存储，DB 不存文件内容
--     用 biz_type + biz_id 泛化关联，加新业务类型无需改表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_file_storage` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '文件名',
    `storage_path`  VARCHAR(512) NOT NULL COMMENT '存储路径（磁盘或 OSS Key）',
    `file_type`     VARCHAR(32)  NOT NULL DEFAULT 'ATTACHMENT' COMMENT '文件类型（字典 file_type）',
    `biz_type`      VARCHAR(64)  NULL COMMENT '关联业务类型（泛化，如 invoice/receipt）',
    `biz_id`        BIGINT       NULL COMMENT '关联业务 ID',
    `uploader_id`   BIGINT       NULL COMMENT '上传人 ID',
    `file_size`     BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `mime_type`     VARCHAR(128) NULL COMMENT 'MIME 类型',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_file_biz` (`tenant_id`, `biz_type`, `biz_id`),
    KEY `idx_file_type` (`tenant_id`, `file_type`),
    KEY `idx_file_uploader` (`tenant_id`, `uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件存储（只存路径）';

-- ---------------------------------------------------------------------------
-- 15. sys_file_download_log 文件下载记录（第四层：可物理删除）
--     ⚠️ download_count 为需求文档卡片漏项，规则 2028 行要求，方案补齐
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_file_download_log` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `file_id`        BIGINT      NOT NULL COMMENT '文件 ID → sys_file_storage.id',
    `biz_type`       VARCHAR(64) NULL COMMENT '关联业务类型',
    `biz_id`         BIGINT      NULL COMMENT '关联业务 ID',
    `downloader_id`  BIGINT      NULL COMMENT '下载人 ID',
    `downloaded_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时间',
    `download_ip`    VARCHAR(64) NULL COMMENT '下载 IP',
    `download_count` INT         NOT NULL DEFAULT 1 COMMENT '下载次数（文档卡片漏项，方案补）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_dl_file` (`tenant_id`, `file_id`),
    KEY `idx_dl_time` (`tenant_id`, `downloaded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件下载记录';

-- ---------------------------------------------------------------------------
-- 16. sys_dict_type 字典类型（唯一用业务编码做 FK 的表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_type` (
    `id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `type_code` VARCHAR(64)  NOT NULL COMMENT '类型编码（业务键）',
    `type_name` VARCHAR(128) NOT NULL COMMENT '类型名称',
    `status`    VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `remark`    VARCHAR(512) NULL COMMENT '备注',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（V1 统一 0=平台字典）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_dict_type_code` (`tenant_id`, `type_code`),
    KEY `idx_dict_type_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典类型';

-- ---------------------------------------------------------------------------
-- 17. sys_dict_data 字典数据（type_code 用业务编码关联 dict_type，非自增 ID）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_data` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `type_code`  VARCHAR(64)  NOT NULL COMMENT '类型编码 → sys_dict_type.type_code（业务编码 FK）',
    `dict_label` VARCHAR(128) NOT NULL COMMENT '字典标签（展示名）',
    `dict_value` VARCHAR(64)  NOT NULL COMMENT '字典键值（存库值）',
    `sort_no`    INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `style`      VARCHAR(32)  NULL COMMENT '样式（字典 dict_style：primary/success/danger）',
    `is_default` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认：0否 1是',
    `status`     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `remark`     VARCHAR(512) NULL COMMENT '备注',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（V1 统一 0=平台字典）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_dict_value` (`tenant_id`, `type_code`, `dict_value`),
    KEY `idx_dict_data_type` (`tenant_id`, `type_code`, `sort_no`),
    KEY `idx_dict_data_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典数据';

-- ---------------------------------------------------------------------------
-- 18. sys_config 系统配置（KV 结构）
--     V1 预置 5 个键：sys_initialized / trial_mode / force_change_password
--                       last_reset_time / last_reset_by
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(512) NULL COMMENT '配置值',
    `config_type`  VARCHAR(32)  NOT NULL DEFAULT 'SYSTEM' COMMENT '配置类型（字典 config_type）',
    `remark`       VARCHAR(512) NULL COMMENT '说明',
    `updated_by`   BIGINT       NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（V1 统一 0=平台配置）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_config_key` (`tenant_id`, `config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统配置';

-- ============================================================================
-- 第三部分：RBAC 7 张（sys_，三层：停用代替删除，故无逻辑删除三字段）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 19. sys_role 角色（父角色继承不超过 3 层）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `role_code`        VARCHAR(64) NOT NULL COMMENT '角色编码',
    `role_name`        VARCHAR(64) NOT NULL COMMENT '角色名称',
    `parent_id`        BIGINT      NULL COMMENT '父角色 ID（自引用，继承≤3 层）',
    `data_scope`       VARCHAR(32) NOT NULL DEFAULT 'SELF' COMMENT '数据范围（字典 data_scope）',
    `custom_dept_ids`  JSON        NULL COMMENT '自定义部门 ID 集合（data_scope=CUSTOM 时生效）',
    `sort_no`          INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`           VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',
    `remark`           VARCHAR(512) NULL COMMENT '备注',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_role_code` (`tenant_id`, `role_code`),
    KEY `idx_role_parent` (`tenant_id`, `parent_id`),
    KEY `idx_role_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色';

-- ---------------------------------------------------------------------------
-- 20. sys_permission 权限
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `perm_code`     VARCHAR(64)  NOT NULL COMMENT '权限编码',
    `perm_name`     VARCHAR(128) NOT NULL COMMENT '权限名称',
    `resource_type` VARCHAR(32)  NOT NULL COMMENT '资源类型（字典 permission_resource）',
    `action_type`   VARCHAR(32)  NOT NULL COMMENT '操作类型（字典 permission_action）',
    `parent_id`     BIGINT       NULL COMMENT '父权限 ID（自引用）',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_perm_code` (`tenant_id`, `perm_code`),
    KEY `idx_perm_parent` (`tenant_id`, `parent_id`),
    KEY `idx_perm_resource` (`tenant_id`, `resource_type`, `action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限';

-- ---------------------------------------------------------------------------
-- 21. sys_menu 菜单（目录/菜单/按钮三级树）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_menu` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `menu_name`    VARCHAR(128) NOT NULL COMMENT '菜单名称',
    `parent_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单 ID（自引用，0=根）',
    `route_path`   VARCHAR(255) NULL COMMENT '路由路径',
    `component`    VARCHAR(255) NULL COMMENT '前端组件',
    `perm_code`    VARCHAR(64)  NULL COMMENT '所需权限编码 → sys_permission.perm_code',
    `menu_type`    VARCHAR(32)  NOT NULL DEFAULT 'MENU' COMMENT '菜单类型（字典 menu_type：目录/菜单/按钮）',
    `sort_no`      INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `visible`      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否可见：0隐藏 1可见',
    `status`       VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by`   BIGINT      NULL COMMENT '更新人 ID',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    KEY `idx_menu_parent` (`tenant_id`, `parent_id`, `sort_no`),
    KEY `idx_menu_perm` (`tenant_id`, `perm_code`),
    KEY `idx_menu_type` (`tenant_id`, `menu_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单';

-- ---------------------------------------------------------------------------
-- 22. sys_user_role 用户-角色（多对多）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `user_id`    BIGINT      NOT NULL COMMENT '用户 ID → sys_user.id',
    `role_id`    BIGINT      NOT NULL COMMENT '角色 ID → sys_role.id',
    `granted_by` BIGINT      NULL COMMENT '授权人 ID',
    `granted_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`tenant_id`, `user_id`, `role_id`),
    KEY `idx_user_role_user` (`tenant_id`, `user_id`),
    KEY `idx_user_role_role` (`tenant_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户-角色关联';

-- ---------------------------------------------------------------------------
-- 23. sys_role_permission 角色-权限（多对多）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `role_id`       BIGINT      NOT NULL COMMENT '角色 ID → sys_role.id',
    `permission_id` BIGINT      NOT NULL COMMENT '权限 ID → sys_permission.id',
    `status`        VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT '状态（字典 common_status）',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`tenant_id`, `role_id`, `permission_id`),
    KEY `idx_role_perm_role` (`tenant_id`, `role_id`),
    KEY `idx_role_perm_perm` (`tenant_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-权限关联';

-- ---------------------------------------------------------------------------
-- 24. sys_role_menu 角色-菜单（多对多）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `role_id` BIGINT NOT NULL COMMENT '角色 ID → sys_role.id',
    `menu_id` BIGINT NOT NULL COMMENT '菜单 ID → sys_menu.id',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`tenant_id`, `role_id`, `menu_id`),
    KEY `idx_role_menu_role` (`tenant_id`, `role_id`),
    KEY `idx_role_menu_menu` (`tenant_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-菜单关联';

-- ---------------------------------------------------------------------------
-- 25. sys_role_conflict 互斥角色（职责分离约束）
--     V1 不做代码/缓存硬校验，仅建表承载数据；初始 4 组见V2 脚本
--     约束：销售经理↔出纳、开票员↔出纳、销售人员↔出纳、销售经理↔开票员
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_conflict` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `role_a_id`     BIGINT       NOT NULL COMMENT '角色 A ID → sys_role.id',
    `role_b_id`     BIGINT       NOT NULL COMMENT '角色 B ID → sys_role.id',
    `conflict_desc` VARCHAR(255) NULL COMMENT '互斥说明',

    `tenant_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 ID（企业）',
    `created_by`   BIGINT      NULL COMMENT '创建人 ID',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `ext`          JSON        NULL COMMENT '扩展字段',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_conflict` (`tenant_id`, `role_a_id`, `role_b_id`),
    KEY `idx_conflict_role_a` (`tenant_id`, `role_a_id`),
    KEY `idx_conflict_role_b` (`tenant_id`, `role_b_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='互斥角色约束';

-- ============================================================================
-- 第四部分：索引命名说明（供后续多租户改造参考）
-- ============================================================================
-- 本脚本中所有业务唯一索引均以 uk_tenant_ 前缀命名，且包含 tenant_id 列。
-- 原因：将来开启多租户时，唯一索引必须含 tenant_id，否则
--       A 企业与 B 企业不能有同名角色/字典/配置。
-- 命名统一为 uk_tenant_* 便于届时一把检索定位。
--
-- 例外（未含tenant_id 的唯一索引，均为「一父一子」语义，加租户无意义）：
--   uk_shipment_receivable  (receivable_id)  —— 1 发货 = 1 应收
--   uk_receivable_shipment  (shipment_id)    —— 同上，反向唯一
-- ============================================================================