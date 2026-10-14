package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.UserOptionVO;
import com.firestar.dto.UserRequest;
import com.firestar.dto.UserVO;
import com.firestar.entity.Org;
import com.firestar.entity.Post;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.exception.BizException;
import com.firestar.mapper.OrgMapper;
import com.firestar.mapper.PostMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.UserService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理实现。纪律同 MenuServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 * 多表写（user + user_role）走 @Transactional 保证原子性。
 */
@Service
public class UserServiceImpl implements UserService {

    /** 分页默认每页条数（前端分页器默认规格一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;
    /** 管理员角色编码（持有该角色的用户禁删，保住管理员入口） */
    private static final String ADMIN_ROLE_CODE = "ROLE_ADMIN";
    /** 用户名格式：小写字母开头，可含小写字母、数字、下划线（RuoYi 同款口径） */
    private static final String USERNAME_PATTERN = "^[a-z][a-z0-9_]*$";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final OrgMapper orgMapper;
    private final PostMapper postMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper userMapper, UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper, OrgMapper orgMapper, PostMapper postMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.orgMapper = orgMapper;
        this.postMapper = postMapper;
    }

    @Override
    public List<UserOptionVO> listOptions() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, "ENABLED")
                .orderByAsc(User::getId));
        return users.stream()
                .map(u -> {
                    UserOptionVO vo = new UserOptionVO();
                    vo.setId(u.getId());
                    vo.setNickname(u.getNickname());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public IPage<UserVO> pageUsers(Integer pageNum, Integer pageSize, String username,
                                   String nickname, String status, Long deptId) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 必须先落到局部变量：wrapper.like(condition, column, xxx.trim()) 的参数
        // 是渴求求值，condition=false 时 trim() 也已经执行过，null 直接 NPE
        String usernameKw = username == null || username.isBlank() ? null : username.trim();
        String nicknameKw = nickname == null || nickname.isBlank() ? null : nickname.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .like(usernameKw != null, User::getUsername, usernameKw)
                .like(nicknameKw != null, User::getNickname, nicknameKw)
                .eq(statusEq != null, User::getStatus, statusEq)
                .eq(deptId != null, User::getDeptId, deptId)
                // 后创建的排在前面（id 倒序），新用户不必翻页找
                .orderByDesc(User::getId);
        Page<User> page = userMapper.selectPage(new Page<>(current, size), wrapper);

        // 批量回填 deptName / roleIds / roleNames（三次批量查询，避免 N+1）
        List<User> records = page.getRecords();
        Map<Long, String> deptNames = new HashMap<>();
        Set<Long> deptIds = new HashSet<>();
        for (User u : records) {
            if (u.getDeptId() != null) {
                deptIds.add(u.getDeptId());
            }
        }
        if (!deptIds.isEmpty()) {
            List<Org> depts = orgMapper.selectBatchIds(deptIds);
            for (Org d : depts) {
                deptNames.put(d.getId(), d.getOrgName());
            }
        }
        // 批量回填岗位名称（单岗：sys_user.post_id → sys_post.id）
        Map<Long, String> postNames = new HashMap<>();
        Set<Long> postIds = new HashSet<>();
        for (User u : records) {
            if (u.getPostId() != null) {
                postIds.add(u.getPostId());
            }
        }
        if (!postIds.isEmpty()) {
            List<Post> posts = postMapper.selectBatchIds(postIds);
            for (Post p : posts) {
                postNames.put(p.getId(), p.getPostName());
            }
        }

        Map<Long, List<Long>> roleIdsOfUser = new HashMap<>();
        Map<Long, String> roleNames = new HashMap<>();
        if (!records.isEmpty()) {
            List<Long> userIds = records.stream().map(User::getId).collect(Collectors.toList());
            List<UserRole> userRoles = userRoleMapper.selectList(
                    new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds));
            if (!userRoles.isEmpty()) {
                Set<Long> roleIds = new HashSet<>();
                for (UserRole ur : userRoles) {
                    roleIdsOfUser.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>()).add(ur.getRoleId());
                    roleIds.add(ur.getRoleId());
                }
                List<Role> roles = roleMapper.selectBatchIds(roleIds);
                for (Role r : roles) {
                    roleNames.put(r.getId(), r.getRoleName());
                }
            }
        }

        Page<UserVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<UserVO> vos = new ArrayList<>(records.size());
        for (User u : records) {
            UserVO vo = new UserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setNickname(u.getNickname());
            vo.setDeptId(u.getDeptId());
            vo.setDeptName(deptNames.get(u.getDeptId()));
            List<Long> rIds = roleIdsOfUser.getOrDefault(u.getId(), List.of());
            vo.setRoleIds(rIds);
            vo.setRoleNames(rIds.stream().map(roleNames::get).collect(Collectors.toList()));
            vo.setStatus(u.getStatus());
            vo.setForceChangePassword(u.getForceChangePassword());
            vo.setLastLoginAt(u.getLastLoginAt());
            vo.setPostId(u.getPostId());
            vo.setPostName(postNames.get(u.getPostId()));
            vo.setPhone(u.getPhone());
            vo.setEmail(u.getEmail());
            vos.add(vo);
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Transactional
    public Long createUser(UserRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        String nickname = req.getNickname() == null ? "" : req.getNickname().trim();
        String password = req.getPassword() == null ? "" : req.getPassword();
        if (username.isEmpty()) {
            throw new BizException("用户名不能为空");
        }
        if (!username.matches(USERNAME_PATTERN)) {
            throw new BizException("用户名必须以小写字母开头，只能包含小写字母、数字、下划线");
        }
        if (nickname.isEmpty()) {
            throw new BizException("姓名不能为空");
        }
        if (password.isEmpty() || password.length() < 6) {
            throw new BizException("密码长度至少 6 位");
        }
        Long dupCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (dupCount != null && dupCount > 0) {
            throw new BizException("用户名已存在");
        }
        Long deptId = req.getDeptId();
        if (deptId != null && orgMapper.selectById(deptId) == null) {
            throw new BizException("所属部门不存在");
        }
        List<Long> roleIds = validateRoleIds(req.getRoleIds());
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("用户状态仅支持 ENABLED / DISABLED");
        }

        User user = new User();
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(passwordEncoder.encode(password));
        // 新用户首次登录强制改密（与 V4 种子 admin 账号一致的行为）
        user.setForceChangePassword(1);
        user.setDeptId(deptId);
        user.setPostId(req.getPostId());
        user.setPhone(req.getPhone());
        user.setEmail(req.getEmail());
        user.setStatus(status);
        userMapper.insert(user);
        replaceUserRoles(user.getId(), roleIds);
        return user.getId();
    }

    @Override
    @Transactional
    public void updateUser(Long id, UserRequest req) {
        User exist = userMapper.selectById(id);
        if (exist == null) {
            throw new BizException("用户不存在");
        }
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        if (!username.isEmpty() && !username.equals(exist.getUsername())) {
            throw new BizException("用户名创建后不可修改");
        }
        String nickname = req.getNickname() == null ? "" : req.getNickname().trim();
        if (nickname.isEmpty()) {
            throw new BizException("姓名不能为空");
        }
        Long deptId = req.getDeptId();
        if (deptId != null && orgMapper.selectById(deptId) == null) {
            throw new BizException("所属部门不存在");
        }
        List<Long> roleIds = validateRoleIds(req.getRoleIds());
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("用户状态仅支持 ENABLED / DISABLED");
        }
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setDeptId(deptId);
        user.setPostId(req.getPostId());
        user.setPhone(req.getPhone());
        user.setEmail(req.getEmail());
        user.setStatus(status);
        userMapper.updateById(user);
        replaceUserRoles(id, roleIds);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User exist = userMapper.selectById(id);
        if (exist == null) {
            throw new BizException("用户不存在");
        }
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, id))
                .stream().map(UserRole::getRoleId).collect(Collectors.toList());
        if (!roleIds.isEmpty()) {
            Long adminRoleCount = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                    .in(Role::getId, roleIds)
                    .eq(Role::getRoleCode, ADMIN_ROLE_CODE));
            if (adminRoleCount != null && adminRoleCount > 0) {
                throw new BizException("管理员账号禁止删除，请先调整其角色");
            }
        }
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        if (exist.getUsername().equals(currentUsername)) {
            throw new BizException("不能删除当前登录用户");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
        userMapper.deleteById(id);
    }

    // ===== 私有辅助 =====

    /** 角色 ID 集合存在性校验（去重 + 空集合按「不配角色」处理） */
    private List<Long> validateRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = roleIds.stream().distinct().collect(Collectors.toList());
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<Role>().in(Role::getId, distinct));
        if (count == null || count != distinct.size()) {
            throw new BizException("所选角色不存在");
        }
        return distinct;
    }

    /** 整体替换用户角色关联（先删后插） */
    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        for (Long roleId : roleIds) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }
}
