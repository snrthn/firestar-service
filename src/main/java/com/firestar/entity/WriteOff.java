package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
// autoResultMap：ext（JSON）列的 JacksonTypeHandler 读写依赖它（BaseEntity 注释口径，
// 2026-10-25 反核销原因落 ext.reverseReason 必须开启）
@TableName(value = "fin_write_off", autoResultMap = true)
public class WriteOff extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 核销单号（业务键） */
    private String writeOffNo;

    /** 应收单 ID → fin_receivable.id */
    private Long receivableId;

    /** 收款单 ID → fin_receipt.id */
    private Long receiptId;

    /** 关联项目 ID → fin_project.id（V80 新增，核销口径下推用；历史数据由 V80 迁移回填） */
    private Long projectId;

    /** 本次核销金额 */
    private BigDecimal amount;

    /** 核销日期 */
    private LocalDate writeOffDate;

    /** 核销方式（字典 writeoff_method） */
    private String writeOffMethod;

    /** 核销状态（字典 writeoff_status） */
    private String status;

    /** 关联原核销 ID（反核销链） */
    private Long originalWriteOffId;

    /** 关联退货 ID */
    private Long returnId;

    /** 操作人 ID */
    private Long operatorId;
}
