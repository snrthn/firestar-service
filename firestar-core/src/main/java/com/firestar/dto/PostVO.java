package com.firestar.dto;

import lombok.Data;

/**
 * 岗位列表 / 下拉 VO（管理端展示，无需关联回填）。
 */
@Data
public class PostVO {

    private Long id;

    /** 岗位编码 */
    private String postCode;

    /** 岗位名称 */
    private String postName;

    /** 排序 */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
