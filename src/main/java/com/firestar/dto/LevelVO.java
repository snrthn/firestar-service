package com.firestar.dto;

import lombok.Data;

/**
 * 职级列表 / 下拉 VO（管理端展示，无需关联回填）。
 */
@Data
public class LevelVO {

    private Long id;

    /** 职级编码 */
    private String levelCode;

    /** 职级名称 */
    private String levelName;

    /** 层级数值 */
    private Integer levelNo;

    /** 排序 */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
