package com.firestar.dto;

import lombok.Data;

/**
 * 字典数据表单（新增 / 修改共用）。
 * 与 sys_dict_data 业务列对齐；id 由路径参数传入，不走请求体。
 * typeCode 由页面上下文（路由 query）固定，修改时同样不允许变更所属类型。
 */
@Data
public class DictDataRequest {

    /** 所属类型编码 → sys_dict_type.type_code */
    private String typeCode;

    /** 字典标签（展示名） */
    private String dictLabel;

    /** 字典键值（存库值，同类型下唯一） */
    private String dictValue;

    /** 排序（同级升序，小的靠前） */
    private Integer sortNo;

    /** 样式（前端 tag class：primary/success/info/warning/danger，可空） */
    private String style;

    /** 是否默认：0 否 1 是 */
    private Integer isDefault;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 备注 */
    private String remark;
}
