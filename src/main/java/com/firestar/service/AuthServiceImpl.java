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
import org.springframework.context.ApplicationEventPublisher;
import com.firestar.realtime.RealtimeEvent;
import com.firestar.realtime.RealtimeEventType;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final ApplicationEventPublisher eventPublisher;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthServiceImpl(UserMapper userMapper, UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                           PermissionMapper permissionMapper, RoleMenuMapper roleMenuMapper,
                           MenuMapper menuMapper, OrgMapper orgMapper, PostMapper postMapper,
                           TokenBlacklistService blacklistService,
                           JwtUtil jwtUtil, JwtProperties jwtProperties,
                           ApplicationEventPublisher eventPublisher) {
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
        this.eventPublisher = eventPublisher;
    }

    @Override
    public LoginResponse login(LoginRequest req, String clientIp) {
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

        // 生成 token（含唯一 jti），登录即轮换「当前有效会话」
        String token = jwtUtil.generateToken(user.getUsername(), roleCodes);

        // 最后登录时间 / IP / current_jti 一并写入。current_jti 覆盖旧值即单点登录核心：
        // 该账号其它已登录设备的 token 因 jti 不再匹配，下次请求即被 filter 拒绝。
        // clientIp 为 null（取不到时）不写该列，保留历史值而不是覆盖成空
        LambdaUpdateWrapper<User> upd = new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLoginAt, LocalDateTime.now())
                .set(User::getCurrentJti, jwtUtil.getJti(token));
        if (clientIp != null) {
            upd.set(User::getLastLoginIp, clientIp);
        }
        userMapper.update(null, upd);

        // 单点登录：登录即轮换 current_jti，旧设备 token 的 jti 不再匹配。
        // 发布 FORCE_LOGOUT 事件，推送网关找到该用户旧 SSE 连接即时通知其失效（毫秒级），
        // 不再依赖 5s 心跳轮询。新设备 SSE 尚未建立，不受影响。
        eventPublisher.publishEvent(new RealtimeEvent(this, RealtimeEventType.FORCE_LOGOUT, user.getUsername(), null));

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
        // 单点登录：登出时清掉当前会话标识（仅清匹配 jti 的那条，已被踢的旧会话无影响），
        // 双保险确保该 token 即便未过期也无法再匹配 current_jti
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getCurrentJti, jti)
                .set(User::getCurrentJti, null));
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
        // 直属上级（方案 C）：parentId 显式覆盖优先；否则回落所属部门负责人，
        // 沿部门树向上找最近的「有负责人且非本人」的上级部门。都找不到则留 null（页面显示 —）。
        vo.setParentName(resolveParentName(user));
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /**
     * 推算个人中心「直属上级」昵称：
     * 1) user.parentId 有值 → 该用户昵称（显式覆盖，最高优先）
     * 2) 否则从 user.deptId 所属部门起，取本部门 leaderId；
     *    若本部门无负责人或本人即负责人，则沿 org.parentId 向上找最近一个
     *    「有负责人且非本人」的上级部门
     * 3) 都找不到 → null（前端显示 —）
     * 防御：org 自引用树可能的环用 guard 次数 + parentId==0(根) 双重截断。
     */
    private String resolveParentName(User user) {
        if (user == null) return null;
        // 1. 显式覆盖
        if (user.getParentId() != null) {
            User p = userMapper.selectById(user.getParentId());
            return p != null ? p.getNickname() : null;
        }
        // 2. 部门链推断
        Long deptId = user.getDeptId();
        int guard = 0;
        while (deptId != null && deptId != 0 && guard++ < 20) {
            Org org = orgMapper.selectById(deptId);
            if (org == null) break;
            Long leaderId = org.getLeaderId();
            if (leaderId != null && !leaderId.equals(user.getId())) {
                User leader = userMapper.selectById(leaderId);
                if (leader != null) return leader.getNickname();
            }
            deptId = org.getParentId(); // 向上一级
        }
        return null;
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
        // 父链处理（两遍，口径 = 全表可见且启用，与授权无关）：
        // 目录是容器、授权粒度在叶子。授权结果集可能缺父组 id——el-tree 父子联动下
        // 取消个别子项会让父组呈半选，旧版前端不提交半选父 id，授权集合里就没有父组
        // （虎哥 2026-10-15 实测：只移除菜单组中的一项，整个组都不返回了）。
        //   第一遍：祖先链断裂（停用/隐藏）的授权项整棵剔除——否则 MenuTreeUtil
        //           会把孤儿提到根级、停用目录的子项顶级裸奔（虎哥 2026-10-13 实测）；
        //   第二遍：可见且启用但未授权的祖先前补为容器，组照常返回、只少被移除的那一项。
        // 注意只处理侧栏这份数据；菜单管理页走 MenuService 全量不过滤，行为不同。
        List<Menu> aliveMenus = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getVisible, 1)
                .eq(Menu::getStatus, "ENABLED"));
        Map<Long, Menu> aliveById = new HashMap<>();
        for (Menu m : aliveMenus) {
            aliveById.put(m.getId(), m);
        }
        Map<Long, Menu> treeNodes = new LinkedHashMap<>();
        for (Menu m : menus) {
            if (ancestorChainAlive(m, aliveById)) {
                treeNodes.put(m.getId(), m);
            }
        }
        for (Menu m : new ArrayList<>(treeNodes.values())) {
            Long cur = m.getParentId();
            while (cur != null && cur != 0 && !treeNodes.containsKey(cur)) {
                Menu ancestor = aliveById.get(cur);
                if (ancestor == null) {
                    break; // 理论不可达：第一遍已保证存活项的祖先全部在 aliveById 内
                }
                treeNodes.put(cur, ancestor);
                cur = ancestor.getParentId();
            }
        }
        return MenuTreeUtil.build(new ArrayList<>(treeNodes.values()));
    }

    /**
     * 沿 parentId 链上溯，每个祖先都必须仍存活（visible=1 且 status=ENABLED，取自全表）。
     * 存活口径与授权无关：父组未被授权（如半选未提交）不算断链，调用方会把它补成容器；
     * 父组被停用/隐藏才算断链，对应子项整棵剔除（防顶级裸奔）。
     * seen 防 parentId 成环时死循环（脏数据兜底，正常数据由 MenuServiceImpl.validateNoCycle 保证）。
     */
    private boolean ancestorChainAlive(Menu node, Map<Long, Menu> aliveById) {
        Long cur = node.getParentId();
        Set<Long> seen = new HashSet<>();
        while (cur != null && cur != 0) {
            if (!seen.add(cur) || !aliveById.containsKey(cur)) {
                return false;
            }
            cur = aliveById.get(cur).getParentId();
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
