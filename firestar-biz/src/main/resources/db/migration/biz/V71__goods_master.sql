-- ============================================================================
-- V71：货物主表（fin_goods）+ fin_item 挂 goods_id
-- 依赖：V1（fin_item 表及 item_name 列已建）、V68（客户主表范式，本次镜像）
--
-- 背景：fin_item 是「按项目挂的货物行」（每行带 project_id + 规格/单位/数量/单价），
--       不是全局货物目录。项目货物名因此是自由文本，同一货物（如「螺丝」）在 N 个项目里
--       是 N 个字符串，无法做「按货物维度」去重统计（同 V68 客户问题）。
--       本迁移建立 fin_goods 作为货物唯一事实源，fin_item 通过 goods_id 关联。
--
-- 范围（虎哥 2026-10-26 拍板「B 建货物主表，像客户」）：
--   ① 只做主表 + 回填 + 项目货物行关联下拉，不做独立 CRUD 页面/菜单/权限点；
--   ② fin_goods 仅存 goods_name（唯一）+ goods_no/status/审计列，规格/单位不放主表
--      （它们是合同专属，选中货物只带出名称，规格/单位仍在项目行手填）；
--   ③ 货物行仍保留自己的规格/单位/数量/单价（合同标的，不进主表）。
--
-- 幂等：INSERT...SELECT 用 NOT EXISTS 去重；ALTER 用 information_schema 判列 + 动态 SQL；
--       UPDATE 带 (goods_id 未关联) 条件。重跑安全。
-- ⚠️ DatabaseCheckService 同步：EXPECTED_TABLE_COUNT 40→41；本迁移不新增字典行。
-- ============================================================================

-- ========== 1. fin_goods 货物主表（镜像 fin_customer，仅名称唯一） ==========
CREATE TABLE IF NOT EXISTS `fin_goods` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `goods_no`       VARCHAR(32)   NULL COMMENT '货物编号（GOODS-{yyyyMMdd}-{id 四位补齐}，回填后生成）',
    `goods_name`     VARCHAR(128)  NOT NULL COMMENT '货物名称（全局唯一，去重事实源）',
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
    UNIQUE KEY `uk_goods_no`     (`tenant_id`, `is_deleted`, `goods_no`),
    UNIQUE KEY `uk_goods_name`   (`tenant_id`, `is_deleted`, `goods_name`),
    KEY `idx_goods_name`         (`tenant_id`, `is_deleted`, `goods_name`),
    KEY `idx_goods_status`       (`tenant_id`, `is_deleted`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='货物主表（V71：看板货物维度唯一事实源）';

-- ========== 2. fin_item 挂 goods_id ==========
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_item' AND COLUMN_NAME = 'goods_id'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE `fin_item` ADD COLUMN `goods_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''货物 ID → fin_goods.id（0=未关联；V71 引入货物主表）'' AFTER `item_name`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 3. 回填：从存量货物名去重建档 ==========
INSERT INTO `fin_goods`
    (`goods_no`, `goods_name`, `status`, `remark`, `tenant_id`)
SELECT
    NULL,
    t.item_name,
    'ENABLED',
    'V71 回填：由存量 fin_item.item_name 去重生成',
    t.tenant_id
FROM (
    SELECT DISTINCT i.tenant_id, i.item_name
    FROM `fin_item` i
    WHERE i.is_deleted = 0
      AND i.item_name IS NOT NULL
      AND TRIM(i.item_name) <> ''
) t
WHERE NOT EXISTS (
    SELECT 1 FROM `fin_goods` g
    WHERE g.goods_name = t.item_name AND g.tenant_id = t.tenant_id
);

-- ========== 4. 生成货物编号 GOODS-{yyyyMMdd}-{id 四位补齐} ==========
UPDATE `fin_goods`
SET `goods_no` = CONCAT('GOODS-', DATE_FORMAT(`created_at`, '%Y%m%d'), '-', LPAD(`id`, 4, '0'))
WHERE `goods_no` IS NULL OR `goods_no` = '';

-- ========== 5. 回填：货物行勾连 goods id ==========
UPDATE `fin_item` i
JOIN `fin_goods` g
  ON g.goods_name = i.item_name AND g.tenant_id = i.tenant_id AND g.is_deleted = 0
SET i.goods_id = g.id
WHERE i.is_deleted = 0
  AND (i.goods_id IS NULL OR i.goods_id = 0)
  AND i.item_name IS NOT NULL
  AND TRIM(i.item_name) <> '';
