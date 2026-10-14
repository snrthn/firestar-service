package com.firestar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.common.JwtUtil;
import com.firestar.common.MenuTreeUtil;
import com.firestar.config.JwtProperties;
import com.firestar.dto.ChangePasswordRequest;
import com.firestar.dto.LoginRequest;
import com.firestar.dto.LoginResponse;
import com.firestar.dto.MenuVO;
import com.firestar.dto.ProfileUpdateRequest;
import com.firestar.dto.ProfileVO;
import com.firestar.dto.RoleVO;
import com.firestar.entity.Menu;
import com.firestar.entity.Org;
import com.firestar.entity.Permission;
import com.firestar.entity.Post;
import com.firestar.entity.Role;
import com.firestar.entity.RoleMenu;
import com.firestar.entity.RolePermission;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.exception.BizException;
import com.firestar.mapper.MenuMapper;
import com.firestar.mapper.OrgMapper;
import com.firestar.mapper.PermissionMapper;
import com.firestar.mapper.PostMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.RoleMenuMapper;
import com.firestar.mapper.RolePermissionMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.TokenBlacklistService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 认证服务实现。
 * 角色 / 权限 / 菜单均通过 RBAC 关联表实时查询，不缓存（保证权限变更即时生效）。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;
    private final OrgMapper orgMapper;
    private final PostMapper postMapper;
    private final TokenBlacklistService blacklistService;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthServiceImpl(UserMapper userMapper, UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                           PermissionMapper permissionMapper, RoleMenuMapper roleMenuMapper,
                           MenuMapper menuMapper, OrgMapper orgMapper, PostMapper postMapper,
                           TokenBlacklistService blacklistService,
                           JwtUtil jwtUtil, JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
        this.orgMapper = orgMapper;
        this.postMapper = postMapper;
        this.blacklistService = blacklistService;
        this.jwtUtil = jwtUtil;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null) {
            throw new BizException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (!"ENABLED".equals(user.getStatus())) {
            throw new BizException("账号已被禁用，请联系管理员");
        }
        List<RoleVO> roles = loadRoles(user.getId());
        List<String> roleCodes = roles.stream().map(RoleVO::getRoleCode).toList();

        // 仅更新最后登录时间，不触碰密码等其它列
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLoginAt, LocalDateTime.now()));

        String token = jwtUtil.generateToken(user.getUsername(), roleCodes);

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(jwtProperties.getExpiration());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setForceChangePassword(user.getForceChangePassword() != null && user.getForceChangePassword() == 1);
        resp.setRoles(roleCodes);
        return resp;
    }

    @Override
    public void logout(String token) {
        if (token == null) {
            return;
        }
        String jti = jwtUtil.getJti(token);
        String username = jwtUtil.getUsername(token);
        Date exp = jwtUtil.getExpiration(token);
        LocalDateTime expireAt = exp.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        blacklistService.blacklist(jti, username, expireAt);
    }

    @Override
    public ProfileVO profile() {
        User user = currentUser();
        List<RoleVO> roles = loadRoles(user.getId());
        List<String> roleCodes = roles.stream().map(RoleVO::getRoleCode).toList();
        List<String> permissions = loadPermissions(roleCodes);

        ProfileVO vo = new ProfileVO();
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setForceChangePassword(user.getForceChangePassword() != null && user.getForceChangePassword() == 1);
        vo.setRoles(roles);
        vo.setPermissions(permissions);
        // 个人中心卡片展示用的本人可见字段（V 新增）
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        if (user.getDeptId() != null) {
            Org org = orgMapper.selectById(user.getDeptId());
            if (org != null) vo.setDeptName(org.getOrgName());
        }
        if (user.getPostId() != null) {
            Post post = postMapper.selectById(user.getPostId());
            if (post != null) vo.setPostName(post.getPostName());
        }
        if (user.getParentId() != null) {
            User leader = userMapper.selectById(user.getParentId());
            if (leader != null) vo.setParentName(leader.getNickname());
        }
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    @Override
    public void changePassword(ChangePasswordRequest req) {
        User user = currentUser();
        // 强制改密场景下允许不校验旧密码；否则必须校验原密码
        if (user.getForceChangePassword() == null || user.getForceChangePassword() == 0) {
            if (req.getOldPassword() == null || !passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
                throw new BizException("原密码不正确");
            }
        }
        if (req.getNewPassword() == null || req.getNewPassword().length() < 6) {
            throw new BizException("新密码长度至少 6 位");
        }
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setForceChangePassword(0);
        userMapper.updateById(user);
    }

    @Override
    public void updateProfile(ProfileUpdateRequest req) {
        User user = currentUser();
        if (req.getNickname() == null || req.getNickname().trim().isEmpty()) {
            throw new BizException("昵称不能为空");
        }
        if (req.getPhone() != null && !req.getPhone().isEmpty()
                && !req.getPhone().matches("^1[3-9]\\d{9}$")) {
            throw new BizException("手机号格式不正确");
        }
        if (req.getEmail() != null && !req.getEmail().isEmpty()
                && !req.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new BizException("邮箱格式不正确");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getNickname, req.getNickname().trim())
                .set(User::getPhone, req.getPhone() == null ? null : req.getPhone())
                .set(User::getEmail, req.getEmail() == null ? null : req.getEmail()));
    }

    @Override
    public List<MenuVO> menus() {
        User user = currentUser();
        List<Long> roleIds = loadRoleIds(user.getId());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenu>().in(RoleMenu::getRoleId, roleIds));
        Set<Long> menuIds = roleMenus.stream().map(RoleMenu::getMenuId).collect(Collectors.toSet());
        if (menuIds.isEmpty()) {
            return List.of();
        }
        List<Menu> menus = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .in(Menu::getId, menuIds)
                .eq(Menu::getVisible, 1)
                .eq(Menu::getStatus, "ENABLED")
                .orderByAsc(Menu::getSortNo));
        // 父链断裂剪枝：祖先被停用/隐藏的子菜单整棵剔除。
        // 不剪的话 MenuTreeUtil 会把孤儿（父不在集合内）提到根级——
        // 停用目录后它的子项以顶级菜单裸奔（虎哥 2026-10-13 实测）。
        // 注意只剪侧栏这份数据；菜单管理页走 MenuService 全量不过滤，
        // 那里需要看到停用项与父子关系，行为不同。
        Set<Long> aliveIds = new HashSet<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Menu m : menus) {
            aliveIds.add(m.getId());
            if (m.getParentId() != null) {
                parentOf.put(m.getId(), m.getParentId());
            }
        }
        List<Menu> pruned = menus.stream()
                .filter(m -> ancestorChainAlive(m, aliveIds, parentOf))
                .collect(Collectors.toList());
        return MenuTreeUtil.build(pruned);
    }

    /**
     * 沿 parentId 链上溯，每个祖先都必须仍在存活集合（visible=1 且 status=ENABLED）内。
     * seen 防 parentId 成环时死循环（脏数据兜底，正常数据由 MenuServiceImpl.validateNoCycle 保证）。
     */
    private boolean ancestorChainAlive(Menu node, Set<Long> aliveIds, Map<Long, Long> parentOf) {
        Long cur = node.getParentId();
        Set<Long> seen = new HashSet<>();
        while (cur != null && cur != 0) {
            if (!seen.add(cur) || !aliveIds.contains(cur)) {
                return false;
            }
            cur = parentOf.get(cur);
        }
        return true;
    }

    // ===== 私有辅助 =====

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BizException(401, "用户不存在或登录已失效");
        }
        return user;
    }

    private List<Long> loadRoleIds(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        return userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toList());
    }

    private List<RoleVO> loadRoles(Long userId) {
        List<Long> roleIds = loadRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getId, roleIds));
        return roles.stream().map(r -> {
            RoleVO vo = new RoleVO();
            vo.setRoleCode(r.getRoleCode());
            vo.setRoleName(r.getRoleName());
            return vo;
        }).collect(Collectors.toList());
    }

    private List<String> loadPermissions(List<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return List.of();
        }
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getRoleCode, roleCodes));
        List<Long> roleIds = roles.stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
        Set<Long> permIds = rps.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
        List<Permission> perms = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .in(Permission::getId, permIds));
        return perms.stream().map(Permission::getPermCode).collect(Collectors.toList());
    }
}
