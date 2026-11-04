package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * Tab④ 项目与销售 榜单载体（rows 已按合同额降序；total 为命中项目数）。
 */
@Data
public class ProjectRankingVO {

    private List<ProjectRankingRowVO> rows = java.util.Collections.emptyList();

    private Long total = 0L;
}
