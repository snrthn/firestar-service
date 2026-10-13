package com.firestar.dto;

import lombok.Data;

/**
 * 字典类型输出（与 MenuVO 同风格，只暴露业务列，
 * version / tenant_id / 审计字段不下发前端）。
 */
@Data
public class DictTypeVO {

    private Long id;

    /** 类型编码（业务键） */
    private String typeCode;

    /** 类型名称 */
    private String typeName;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 备注 */
    private String remark;
}
