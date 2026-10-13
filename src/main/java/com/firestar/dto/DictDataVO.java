package com.firestar.dto;

import lombok.Data;

/**
 * 字典数据输出（业务列，审计/乐观锁字段不下发）。
 */
@Data
public class DictDataVO {

    private Long id;

    /** 所属类型编码 → sys_dict_type.type_code */
    private String typeCode;

    /** 字典标签（展示名） */
    private String dictLabel;

    /** 字典键值（存库值） */
    private String dictValue;

    /** 排序 */
    private Integer sortNo;

    /** 样式（tag class） */
    private String style;

    /** 是否默认：0 否 1 是 */
    private Integer isDefault;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 备注 */
    private String remark;
}
