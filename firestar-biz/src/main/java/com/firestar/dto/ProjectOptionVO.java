package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 项目下拉选项（看板「按项目筛选」用，V80；仿 CustomerOptionVO）。
 *
 * <p>返回所有未删除项目（id / projectNo / projectName），按项目编号升序，供前端 el-select 用。</p>
 */
@Getter
@Setter
public class ProjectOptionVO {
    private Long id;
    private String projectNo;
    private String projectName;
}
