package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.PermissionRequest;
import com.firestar.dto.PermissionVO;

import java.util.List;

/**
 * 权限管理服务（树形 CRUD）。
 *
 * 关键业务规则：
 *  - permCode 创建后不可改（程序 @PreAuthorize 按编码引用，同 username / role_code 纪律）；
 *  - 删除校验：有子权限拒删；被角色引用（sys_role_permission 有关联）拒删；
 *  - 防环：上级权限不能是自己或自己的子孙（同 OrgServiceImpl.validateNoCycle）；
 *  - 现有 V4 种子权限均为平铺（parent_id 为空），树形展示时自然落在根级。
 */
public interface PermissionService {

    /** 全量权限树（管理页展示用，按 id 升序） */
    List<PermissionVO> listTree();

    /** 分页权限列表（permName/permCode 模糊、status 精确；默认 1/10，size 上限 500；回填 parentName） */
    IPage<PermissionVO> pagePerms(Integer pageNum, Integer pageSize, String permName, String permCode, String status);

    /** 创建权限（permCode 查重；返回新权限 id） */
    Long create(PermissionRequest req);

    /** 修改权限（permCode 不可改） */
    void update(Long id, PermissionRequest req);

    /** 删除权限（子权限 / 角色引用 拒删） */
    void delete(Long id);
}
