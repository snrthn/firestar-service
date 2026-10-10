package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_dict_type")
public class DictType extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 类型编码（业务键） */
    private String typeCode;

    /** 类型名称 */
    private String typeName;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
