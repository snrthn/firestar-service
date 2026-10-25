-- ============================================================
-- V65：回款/开票双向核销（虎哥 2026-10-25 拍板 A/B/C/D）
--
-- 背景（项目 PJ-20261024-0155 两问题收尾）：
--   ① 项目详情「累计已收」读 fin_shipment.received_amount 聚合，而该列全库无写入方
--      （唯一写点是发货单创建时置 0）→ 所有项目累计已收恒 0；
--      fin_project.collected_amount / invoiced_amount 早有写入方（审批通过累加），
--      但 ProjectVO 从未接出 → 「写了没人读」。拍板 A：项目三数改实时聚合
--      （Σ RECEIVED 收款单 / Σ ISSUED 发票 / Σ 应收单），shipment 口径废弃。
--   ② 收款单不知关联哪个发货批次：V1 baseline 的 fin_write_off（收款↔应收核销明细）
--      与 writeoff_status/writeoff_method 字典早已设计完备，但从未有代码写它；
--      fin_receivable.invoiced/uninvoiced、fin_receipt.written_off/unwritten_off
--      同样只有建列没有写方。拍板 B：双向拆分——复用 fin_write_off 承载
--      「收款单↔多张应收单」多对多核销；发票侧无对应明细表，本迁移新建。
--   ③ 拍板 C：撤回回退——待审批态编辑分配走「硬删重插」（数字未滚无痕需求）；
--      已通过后撤回走反核销（status=REVERSED + original_*_id 链，同 V1 范式注释）。
--
-- ⚠️ 取值口径（同 V1 fin_write_off，勿自行改写大小写/连字符）：
--   fin_write_off.status / fin_invoice_allocation.status 复用字典 writeoff_status
--   （V2 已种 NORMAL/REVERSED，V62 已归一 status 域值 ENABLED）。
--   核销单号范式同 generateReceiptNo()：WO-yyyyMMdd-%03d（count+1）。
--
-- ⚠️ 表数量账（DatabaseCheckService.EXPECTED_TABLE_COUNT，历史上已四次因漏同步误报）：
--   本迁移新增 1 张业务表（fin_invoice_allocation）→ 37 改 38；
--   不新增字典行（writeoff_status/writeoff_method 为 V2 既有种子）→ dict 计数不变。
--
-- 幂等：CREATE TABLE / ADD COLUMN 由 Flyway 保证只执行一次（同 V59 风格，不用 IF NOT EXISTS）。
-- 依赖：V1（fin_write_off / fin_receivable 基线）、V2（writeoff_status 字典）、V62（status 归一）
-- ============================================================

-- 1. fin_receivable 补齐回款侧核销两列（与既有 invoiced/uninvoiced 同构；
--    written_off/unwritten_off 为收款单自身核销口径，received/unreceived 为应收单回款口径，
--    二者经 fin_write_off 明细双向守恒，不可混用）
ALTER TABLE `fin_receivable`
    ADD COLUMN `received_amount`  DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '已回款金额（收款核销通过累加）',
    ADD COLUMN `unreceived_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '未回款金额（应收金额-已回款）';

-- ---------------------------------------------------------------------------
-- 2. fin_invoice_allocation 发票核销明细 —— 发票与应收单的多对多匹配
--    严格同构 V1 fin_write_off（L268-299）：invoice_id 替换 receipt_id，
--    核销必须单事务：改应收已开票 + 改应收未开票 + 插本记录，三者同成同败。
--    拍板 B 双向拆分：一张发票可拆多张应收单（合并开），一张应收单可收多张发票（分期开）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fin_invoice_allocation` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `allocation_no`            VARCHAR(64)   NOT NULL COMMENT '核销单号（业务键，WO-yyyyMMdd-nnn）',
    `invoice_id`               BIGINT        NOT NULL COMMENT '发票 ID → fin_invoice.id',
    `receivable_id`            BIGINT        NOT NULL COMMENT '应收单 ID → fin_receivable.id',
    `amount`                   DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '本次核销（开票分配）金额',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'NORMAL' COMMENT '核销状态（字典 writeoff_status：NORMAL/REVERSED）',
    `original_allocation_id`   BIGINT        NULL COMMENT '关联原核销 ID（反核销链）',

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
    UNIQUE KEY `uk_tenant_allocation_no` (`tenant_id`, `allocation_no`),
    KEY `idx_inv_alloc_invoice` (`tenant_id`, `is_deleted`, `invoice_id`),
    KEY `idx_inv_alloc_receivable` (`tenant_id`, `is_deleted`, `receivable_id`),
    KEY `idx_inv_alloc_status` (`tenant_id`, `is_deleted`, `status`),
    KEY `idx_inv_alloc_original` (`original_allocation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发票核销明细';
