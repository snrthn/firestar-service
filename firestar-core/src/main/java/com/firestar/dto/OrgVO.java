package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 组织架构树节点（/system/org/list）。children 为下级部门，前端递归渲染。
 * leaderName 由服务层按 leaderId 回填（sys_user.nickname），避免前端二次查询。
 */
@Data
public class OrgVO {
    private Long id;
    private Long parentId;
    private String orgName;
    private Long leaderId;
    /** 负责人姓名（leaderId 为空或用户不存在时为空串兜底） */
    private String leaderName;
    private Integer sortNo;
    /** 状态：ENABLED / DISABLED */
    private String status;
    private List<OrgVO> children;
}
