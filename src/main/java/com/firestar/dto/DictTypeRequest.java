package com.firestar.dto;

import lombok.Data;

/**
 * 字典类型表单（新增 / 修改共用）。
 * 与 sys_dict_type 业务列对齐；id 由路径参数传入，不走请求体。
 * 注意：修改时 typeCode 不允许变更（字典数据按 type_code 业务编码关联，
 * 改编码会造成存量数据孤儿），服务层统一以库中现存值为准。
 */
@Data
public class DictTypeRequest {

    /** 类型编码（业务键，创建后不可改） */
    private String typeCode;

    /** 类型名称 */
    private String typeName;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 备注 */
    private String remark;
}
