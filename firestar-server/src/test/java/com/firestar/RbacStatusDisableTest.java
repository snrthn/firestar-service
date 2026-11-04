package com.firestar;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.dto.PermissionRequest;
import com.firestar.dto.ProfileVO;
import com.firestar.dto.RoleRequest;
import com.firestar.dto.UserRequest;
import com.firestar.entity.Permission;
import com.firestar.entity.Role;
import com.firestar.entity.RolePermission;
import com.firestar.entity.UserRole;
import com.firestar.mapper.PermissionMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.RolePermissionMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.AuthService;
import com.firestar.service.PermissionResolver;
import com.firestar.service.PermissionService;
import com.firestar.service.RoleService;
import com.firestar.service.UserRoleService;
import com.firestar.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 A（2026-11-04）：停用语义的回归测试。
 *
 * <p>修复前：sys_role / sys_permission / sys_role_permission 三张表都有 status 字段，
 * 写入时也校验取值，但鉴权读取时全部忽略 —— 停用角色、停用权限、停用关联都形同虚设。
 * 本测试把「启用 → 停用 → 下个请求即失效」钉死在真实库（firestar_test，Flyway 全量重放）上。
 *
 * <p>项目鉴权每次请求实时查库、不缓存，所以不需要 token 撤销 / 黑名单 / 踢人；
 * 测试直接调 Service 模拟「下一个请求」即可，@Transactional 结束自动回滚不污染测试库。
 */
@SpringBootTest
@ActiveProfiles("it")
@Transactional
class RbacStatusDisableTest {

    @Autowired private RoleService roleService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserService userService;
    @Autowired private UserRoleService userRoleService;
    @Autowired private PermissionResolver permissionResolver;
    @Autowired private AuthService authService;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserRoleMapper userRoleMapper;

    private String username;
    private Long userId;
    private Long roleId;
    private Long permId;
    private String roleCode;
    private String permCode;

    @BeforeEach
    void setUp() {
        String u = "dis_" + System.nanoTime();
        username = u;
        roleCode = u;
        permCode = u;

        RoleRequest rr = new RoleRequest();
        rr.setRoleName(u);
        rr.setRoleCode(roleCode);
        roleId = roleService.createRole(rr);

        PermissionRequest pr = new PermissionRequest();
        pr.setPermName(u);
        pr.setPermCode(permCode);
        permId = permissionService.create(pr);

        UserRequest ur = new UserRequest();
        ur.setUsername(u);
        ur.setPassword("临时密码@123");
        ur.setNickname(u);
        userId = userService.createUser(ur);

        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);

        RolePermission rp = new RolePermission();
        rp.setRoleId(roleId);
        rp.setPermissionId(permId);
        rp.setStatus("ENABLED");
        rolePermissionMapper.insert(rp);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 模拟 JwtAuthenticationFilter 每次请求的两步：实时查角色码 → 实时解析权限点 */
    private List<String> permsForNextRequest() {
        return permissionResolver.resolve(userRoleService.findRoleCodesByUserId(userId));
    }

    private void disableRole(Long id) {
        roleMapper.update(null, new LambdaUpdateWrapper<Role>()
                .eq(Role::getId, id).set(Role::getStatus, "DISABLED"));
    }

    private void disablePermission() {
        permissionMapper.update(null, new LambdaUpdateWrapper<Permission>()
                .eq(Permission::getId, permId).set(Permission::getStatus, "DISABLED"));
    }

    private void disableLink() {
        rolePermissionMapper.update(null, new LambdaUpdateWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId)
                .eq(RolePermission::getPermissionId, permId)
                .set(RolePermission::getStatus, "DISABLED"));
    }

    @Test
    @DisplayName("基线：全启用时角色码与权限点都能解析出来")
    void enabledChain_isResolved() {
        assertTrue(userRoleService.findRoleCodesByUserId(userId).contains(roleCode));
        assertTrue(permsForNextRequest().contains(permCode));
    }

    @Test
    @DisplayName("停用角色：下个请求角色码消失、全部权限撤回")
    void disabledRole_revokesAllAuthorities() {
        disableRole(roleId);
        assertFalse(userRoleService.findRoleCodesByUserId(userId).contains(roleCode),
                "停用角色后 findRoleCodesByUserId 不应再返回该角色");
        assertFalse(permsForNextRequest().contains(permCode),
                "停用角色后该角色带来的权限应全部撤回");
    }

    @Test
    @DisplayName("停用权限：全启用角色也拿不到该权限点")
    void disabledPermission_isRevokedGlobally() {
        disablePermission();
        assertTrue(userRoleService.findRoleCodesByUserId(userId).contains(roleCode),
                "停用权限不应影响角色本身");
        assertFalse(permsForNextRequest().contains(permCode),
                "停用权限后所有引用它的角色都不应再拿到该权限点");
    }

    @Test
    @DisplayName("停用角色-权限关联：仅该条授权失效")
    void disabledRolePermissionLink_isRevoked() {
        disableLink();
        assertTrue(userRoleService.findRoleCodesByUserId(userId).contains(roleCode));
        assertFalse(permsForNextRequest().contains(permCode));
    }

    @Test
    @DisplayName("停用角色：/auth/profile 的 roles 也不再返回它")
    void disabledRole_isInvisibleInProfile() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
        ProfileVO before = authService.profile();
        assertTrue(before.getRoles().stream().anyMatch(r -> roleCode.equals(r.getRoleCode())));

        disableRole(roleId);

        ProfileVO after = authService.profile();
        assertFalse(after.getRoles().stream().anyMatch(r -> roleCode.equals(r.getRoleCode())),
                "停用角色后 profile 不应再展示这个角色");
        assertNotNull(after.getPermissions());
    }

    @Test
    @DisplayName("多角色取并集：停用其中一个，另一个角色的权限照常生效")
    void disablingOneRoleKeepsTheOther() {
        String u2 = "dis2_" + System.nanoTime();
        RoleRequest rr2 = new RoleRequest();
        rr2.setRoleName(u2);
        rr2.setRoleCode(u2);
        Long roleId2 = roleService.createRole(rr2);
        PermissionRequest pr2 = new PermissionRequest();
        pr2.setPermName(u2);
        pr2.setPermCode(u2);
        Long permId2 = permissionService.create(pr2);

        UserRole ur2 = new UserRole();
        ur2.setUserId(userId);
        ur2.setRoleId(roleId2);
        userRoleMapper.insert(ur2);
        RolePermission rp2 = new RolePermission();
        rp2.setRoleId(roleId2);
        rp2.setPermissionId(permId2);
        rp2.setStatus("ENABLED");
        rolePermissionMapper.insert(rp2);

        disableRole(roleId);

        List<String> perms = permsForNextRequest();
        assertFalse(perms.contains(permCode), "被停用角色的权限应撤回");
        assertTrue(perms.contains(u2), "另一个启用角色的权限不应被牵连");
    }
}
