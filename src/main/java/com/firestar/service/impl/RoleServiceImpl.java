package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.RoleManageVO;
import com.firestar.dto.RoleMenuRequest;
import com.firestar.dto.RolePermRequest;
import com.firestar.dto.RoleRequest;
import com.firestar.entity.Menu;
import com.firestar.entity.Permission;
import com.firestar.entity.Role;
import com.firestar.entity.RoleMenu;
import com.firestar.entity.RolePermission;
import com.firestar.entity.UserRole;
import com.firestar.annotation.Auditable;
import com.firestar.exception.BizException;
import com.firestar.mapper.MenuMapper;
import com.firestar.mapper.PermissionMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.RoleMenuMapper;
import com.firestar.mapper.RolePermissionMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色管理实现。纪律同 MenuServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class RoleServiceImpl implements RoleService {

    /** 分页默认每页条数（前端分页器默认规格一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;
    /** 管理员角色编码（禁改禁删的根基角色） */
    private static final String ADMIN_ROLE_CODE = "ROLE_ADMIN";
    /** 角色编码格式：字母开头，可含字母、数字、下划线（兼容存量 ROLE_ADMIN 大写风格） */
    private static final String ROLE_CODE_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$";

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;

    public RoleServiceImpl(RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                           UserRoleMapper userRoleMapper, PermissionMapper permissionMapper,
                           MenuMapper menuMapper, RoleMenuMapper roleMenuMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    @Override
    public IPage<RoleManageVO> pageRoles(Integer pageNum, Integer pageSize, String roleName, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 必须先落到局部变量：wrapper.like(condition, column, roleName.trim()) 的
        // 参数是渴求求值，condition=false 时 trim() 也已经执行过，null 直接 NPE
        String nameKw = roleName == null || roleName.isBlank() ? null : roleName.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        Page<Role> page = roleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Role>()
                        .like(nameKw != null, Role::getRoleName, nameKw)
                        .eq(statusEq != null, Role::getStatus, statusEq)
                        .orderByAsc(Role::getSortNo)
                        .orderByAsc(Role::getId));
        Page<RoleManageVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<RoleManageVO> vos = new ArrayList<>(page.getRecords().size());
        for (Role r : page.getRecords()) {
            RoleManageVO vo = new RoleManageVO();
            vo.setId(r.getId());
            vo.setRoleCode(r.getRoleCode());
            vo.setRoleName(r.getRoleName());
            vo.setSortNo(r.getSortNo());
            vo.setStatus(r.getStatus());
            vo.setRemark(r.getRemark());
            vos.add(vo);
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Auditable(operationType = "CREATE", targetType = "ROLE", targetIdSpEl = "#result", entityClass = Role.class, fields = {"roleCode", "roleName", "status", "sortNo", "remark"})
    public Long createRole(RoleRequest req) {
        Role role = new Role();
        applyForm(role, req, null);
        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Auditable(operationType = "UPDATE", targetType = "ROLE", targetIdSpEl = "#id", entityClass = Role.class, fields = {"roleCode", "roleName", "status", "sortNo", "remark"})
    public void updateRole(Long id, RoleRequest req) {
        Role exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new BizException("角色不存在");
        }
        if (ADMIN_ROLE_CODE.equals(exist.getRoleCode())) {
            throw new BizException("管理员角色禁止修改");
        }
        String roleCode = req.getRoleCode() == null ? "" : req.getRoleCode().trim();
        if (!roleCode.isEmpty() && !roleCode.equals(exist.getRoleCode())) {
            throw new BizException("角色编码创建后不可修改");
        }
        Role role = new Role();
        role.setId(id);
        applyForm(role, req, id);
        roleMapper.updateById(role);
    }

    @Override
    @Auditable(operationType = "DELETE", targetType = "ROLE", targetIdSpEl = "#id", entityClass = Role.class, fields = {"roleCode", "roleName", "status"})
    public void deleteRole(Long id) {
        Role exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new BizException("角色不存在");
        }
        if (ADMIN_ROLE_CODE.equals(exist.getRoleCode())) {
            throw new BizException("管理员角色禁止删除");
        }
        Long userCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, id));
        if (userCount != null && userCount > 0) {
            throw new BizException("该角色下有关联用户，请先调整用户角色");
        }
        // 连带清理角色权限 / 菜单关联，避免留下孤儿授权记录（此前只清了 permission，漏了 menu）
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>()
                .eq(RoleMenu::getRoleId, id));
        roleMapper.deleteById(id);
    }

    @Override
    public List<Long> listPermIds(Long roleId) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("角色不存在");
        }
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId))
                .stream().map(RolePermission::getPermissionId).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void assignPerms(Long roleId, RolePermRequest req) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("角色不存在");
        }
        List<Long> permIds = req.getPermIds() == null ? List.of()
                : req.getPermIds().stream().distinct().collect(Collectors.toList());
        if (!permIds.isEmpty()) {
            // 存在性校验要查 sys_permission 表本身，不能查 sys_role_permission
            // （那边只反映「已被角色引用」的权限，会漏判新权限 ID）
            Long count = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>()
                    .in(Permission::getId, permIds));
            if (count == null || count != permIds.size()) {
                throw new BizException("所选权限不存在");
            }
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId));
        for (Long permId : permIds) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            rp.setStatus("ENABLED");
            rolePermissionMapper.insert(rp);
        }
    }

    @Override
    public List<Long> listMenuIds(Long roleId) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("角色不存在");
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId))
                .stream().map(RoleMenu::getMenuId).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void assignMenus(Long roleId, RoleMenuRequest req) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("角色不存在");
        }
        List<Long> menuIds = req.getMenuIds() == null ? List.of()
                : req.getMenuIds().stream().distinct().collect(Collectors.toList());
        if (!menuIds.isEmpty()) {
            // 存在性校验查 sys_menu 表本身，不能查 sys_role_menu
            Long count = menuMapper.selectCount(new LambdaQueryWrapper<Menu>()
                    .in(Menu::getId, menuIds));
            if (count == null || count != menuIds.size()) {
                throw new BizException("所选菜单不存在");
            }
        }
        // 整体替换：先删后插，同事务
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>()
                .eq(RoleMenu::getRoleId, roleId));
        for (Long menuId : menuIds) {
            RoleMenu rm = new RoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }
    }

    // ===== 私有辅助 =====

    /** 表单字段 → 实体（null 走 DDL 默认值：sortNo=0 / status=ENABLED）；selfId 为编辑中的角色 ID（新增传 null） */
    private void applyForm(Role role, RoleRequest req, Long selfId) {
        String roleCode = req.getRoleCode() == null ? "" : req.getRoleCode().trim();
        String roleName = req.getRoleName() == null ? "" : req.getRoleName().trim();
        if (roleCode.isEmpty()) {
            throw new BizException("角色编码不能为空");
        }
        if (!roleCode.matches(ROLE_CODE_PATTERN)) {
            throw new BizException("角色编码必须以字母开头，只能包含字母、数字、下划线");
        }
        if (roleName.isEmpty()) {
            throw new BizException("角色名称不能为空");
        }
        Long codeDup = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode)
                .ne(selfId != null, Role::getId, selfId));
        if (codeDup != null && codeDup > 0) {
            throw new BizException("角色编码已存在");
        }
        Long nameDup = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleName, roleName)
                .ne(selfId != null, Role::getId, selfId));
        if (nameDup != null && nameDup > 0) {
            throw new BizException("角色名称已存在");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("角色状态仅支持 ENABLED / DISABLED");
        }
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        role.setStatus(status);
        role.setRemark(req.getRemark());
    }
}
