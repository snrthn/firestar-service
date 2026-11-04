package com.firestar.dto;

import lombok.Data;

/**
 * 岗位管理表单（创建 / 修改共用）。
 * postCode 创建后不可改（程序按编码引用岗位，同 username / roleCode 纪律）。
 */
@Data
public class PostRequest {

    /** 岗位编码（创建后不可修改；字母开头，可含字母、数字、下划线） */
    private String postCode;

    /** 岗位名称 */
    private String postName;

    /** 排序（同级内数字越小越靠前） */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
