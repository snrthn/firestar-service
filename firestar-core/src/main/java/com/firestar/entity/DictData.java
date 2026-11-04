package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_dict_data")
public class DictData extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 类型编码 → sys_dict_type.type_code（业务编码 FK） */
    private String typeCode;

    /** 字典标签（展示名） */
    private String dictLabel;

    /** 字典键值（存库值） */
    private String dictValue;

    /** 排序 */
    private Integer sortNo;

    /** 样式（字典 dict_style：primary/success/danger） */
    private String style;

    /** 是否默认：0否 1是 */
    private Integer isDefault;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
