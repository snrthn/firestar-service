package com.firestar.dto;

import lombok.Data;

/**
 * 职级管理表单（创建 / 修改共用）。
 * levelCode 创建后不可改（程序按编码引用职级，同 username / roleCode / postCode 纪律）。
 */
@Data
public class LevelRequest {

    /** 职级编码（创建后不可修改；字母开头，可含字母、数字、下划线，如 L1 / L2 / L3） */
    private String levelCode;

    /** 职级名称 */
    private String levelName;

    /** 层级数值（1=最低，逐级+1） */
    private Integer levelNo;

    /** 排序（同级内数字越小越靠前） */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
