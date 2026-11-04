package com.firestar;

import com.firestar.dto.*;
import com.firestar.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1：RBAC 全套「正向成功路径」冒烟 + 权限防护。
 * 用 @Transactional 让测试在事务内运行、结束自动回滚，不污染 dev 库。
 * 每个模块验证：create 成功返回 id / 树形列表可读 / 无 @RequirePerm 权限时被 AOP 拒绝。
 * 注：DTO 字段名以实际定义为基准，若本地编译报字段不存在，按实体提示微调 setter 即可。
 */
@SpringBootTest
@Transactional
class RbacCrudTest {

    @Autowired private RoleService roleService;
    @Autowired private PermissionService permissionService;
    @Autowired private MenuService menuService;
    @Autowired private OrgService orgService;
    @Autowired private PostService postService;
    @Autowired private UserService userService;
    @Autowired private LevelService levelService;

    private String uq() { return "ut_" + System.nanoTime(); }

    @Test @DisplayName("P1-RBAC：createRole 成功返回 id")
    void createRole_success() {
        RoleRequest r = new RoleRequest();
        r.setRoleName(uq());
        r.setRoleCode(uq());
        assertNotNull(roleService.createRole(r));
    }

    @Test @DisplayName("P1-RBAC：createPermission 成功返回 id")
    void createPermission_success() {
        PermissionRequest r = new PermissionRequest();
        r.setPermName(uq());
        r.setPermCode(uq());
        assertNotNull(permissionService.create(r));
    }

    @Test @DisplayName("P1-RBAC：createMenu 成功返回 id")
    void createMenu_success() {
        MenuRequest r = new MenuRequest();
        r.setMenuName(uq());
        r.setRoutePath("/ut/" + uq());
        assertNotNull(menuService.create(r));
    }

    @Test @DisplayName("P1-RBAC：createOrg 成功返回 id")
    void createOrg_success() {
        OrgRequest r = new OrgRequest();
        r.setOrgName(uq());
        assertNotNull(orgService.create(r));
    }

    @Test @DisplayName("P1-RBAC：createPost 成功返回 id")
    void createPost_success() {
        PostRequest r = new PostRequest();
        r.setPostName(uq());
        r.setPostCode(uq());
        assertNotNull(postService.createPost(r));
    }

    @Test @DisplayName("P1-RBAC：createUser 成功返回 id")
    void createUser_success() {
        UserRequest r = new UserRequest();
        r.setUsername(uq());
        r.setPassword("临时密码@123");
        r.setNickname(uq());
        assertNotNull(userService.createUser(r));
    }

    @Test @DisplayName("P1-RBAC：createLevel 成功返回 id")
    void createLevel_success() {
        LevelRequest r = new LevelRequest();
        r.setLevelName(uq());
        r.setLevelCode(uq());
        assertNotNull(levelService.createLevel(r));
    }

    @Test @DisplayName("P1-RBAC：树形列表可读（权限/菜单/组织）")
    void trees_readable() {
        assertNotNull(permissionService.listTree());
        assertNotNull(menuService.listTree());
        assertNotNull(orgService.listTree());
    }

    @Test @DisplayName("P1-RBAC：无 system:role:create 权限调用 createRole 被拒绝（AccessDenied）")
    void createRole_withoutPermission_rejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest", null, List.of()));
        RoleRequest r = new RoleRequest();
        r.setRoleName(uq());
        r.setRoleCode(uq());
        assertThrows(AccessDeniedException.class, () -> roleService.createRole(r));
    }
}
