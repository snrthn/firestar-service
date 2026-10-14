package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.RoleManageVO;
import com.firestar.dto.RoleMenuRequest;
import com.firestar.dto.RolePermRequest;
import com.firestar.dto.RoleRequest;

import java.util.List;

/**
 * 角色管理服务。
 *
 * 关键业务规则：
 *  - roleCode 创建后不可改（程序按编码引用角色，同 username / typeCode 纪律）；
 *  - ROLE_ADMIN 禁改禁删（管理员入口的根基，改名/停用会把系统锁死）；
 *  - 删角色前校验关联用户（sys_user_role 有记录拒删，RuoYi 同款行为）；
 *  - 权限分配为整体替换语义（先删后插，@Transactional）。
 */
public interface RoleService {

    /** 分页角色列表（roleName 模糊、status 精确；默认 1/10，size 上限 500） */
    IPage<RoleManageVO> pageRoles(Integer pageNum, Integer pageSize, String roleName, String status);

    /** 创建角色（roleCode/roleName 查重；返回新角色 id） */
    Long createRole(RoleRequest req);

    /** 修改角色（roleCode 不可改；ROLE_ADMIN 禁改） */
    void updateRole(Long id, RoleRequest req);

    /** 删除角色（ROLE_ADMIN / 关联用户 拒删） */
    void deleteRole(Long id);

    /** 查询角色已授权的权限 ID 集合（分配弹窗回显） */
    List<Long> listPermIds(Long roleId);

    /** 分配权限（整体替换；空集合 = 清空） */
    void assignPerms(Long roleId, RolePermRequest req);

    /** 查询角色已授权的菜单 ID 集合（分配弹窗回显） */
    List<Long> listMenuIds(Long roleId);

    /** 分配菜单（整体替换；空集合 = 清空） */
    void assignMenus(Long roleId, RoleMenuRequest req);
}
