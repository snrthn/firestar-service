package com.firestar.service;

import com.firestar.dto.OrgRequest;
import com.firestar.dto.OrgVO;

import java.util.List;

/**
 * 组织架构（部门）管理服务（/system/org）。
 * 面向管理后台：返回全量部门树（含停用），提供增删改；
 * 删除校验：存在子部门 / 关联用户（sys_user.dept_id）时拒删，
 * 与菜单管理「有子菜单拒删」保持同一强度的约束。
 */
public interface OrgService {

    /** 全量部门树（管理页展示，不过滤 status；leaderName 已回填） */
    List<OrgVO> listTree();

    /** 新增部门，返回新部门 ID */
    Long create(OrgRequest req);

    /** 修改部门 */
    void update(Long id, OrgRequest req);

    /** 删除部门（存在子部门或关联用户时拒删） */
    void delete(Long id);
}
