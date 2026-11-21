package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Org;
import com.firestar.entity.Post;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.mapper.OrgMapper;
import com.firestar.mapper.PostMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.ImportHandler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 期初用户导入 Handler（P0，方案 v3 §5）。
 *
 * <p>直接落 sys_user / sys_user_role，绕过 UserServiceImpl.create 的 Flowable 与审计。
 * 用户名正则与唯一性、组织/岗位/角色存在性在 validate 阶段全量校验；
 * 密码默认初始密码并强制首登修改。组织/岗位/角色按名称或编码引用已存在记录（不存在即报错）。</p>
 */
@Component
public class UserImportHandler implements ImportHandler {

    private static final String F_USERNAME = "username";
    private static final String F_NICKNAME = "nickname";
    private static final String F_PHONE = "phone";
    private static final String F_EMAIL = "email";
    private static final String F_ORG = "orgName";
    private static final String F_POST = "postCode";
    private static final String F_ROLES = "roleCodes";
    private static final String F_STATUS = "status";
    private static final String F_PASSWORD = "password";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final String DEFAULT_PASSWORD = "Firestar@2026";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrgMapper orgMapper;
    private final PostMapper postMapper;
    private final RoleMapper roleMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserImportHandler(UserMapper userMapper, UserRoleMapper userRoleMapper,
                             OrgMapper orgMapper, PostMapper postMapper, RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgMapper = orgMapper;
        this.postMapper = postMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public String key() {
        return "user_opening";
    }

    @Override
    public String displayName() {
        return "期初用户";
    }

    @Override
    public String sheetName() {
        return "用户";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("用户名", F_USERNAME, "必填，小写字母开头，仅小写字母/数字/下划线", "zhangsan"),
                ImportColumnDef.required("姓名", F_NICKNAME, "必填，最长 50 字", "张三"),
                ImportColumnDef.of("手机号", F_PHONE, "需唯一；最长 20 字", "13800138000"),
                ImportColumnDef.of("邮箱", F_EMAIL, "选填", "zhangsan@example.com"),
                ImportColumnDef.of("组织名称", F_ORG, "已存在的组织名称，留空为无部门", "财务部"),
                ImportColumnDef.of("岗位编码", F_POST, "已存在的岗位编码，留空为无岗位", "ACCOUNTANT"),
                ImportColumnDef.of("角色编码", F_ROLES, "多个用英文逗号分隔，须已存在", "ROLE_MASTER,ROLE_USER"),
                ImportColumnDef.of("状态", F_STATUS, "ENABLED / DISABLED，留空默认 ENABLED", "ENABLED"),
                ImportColumnDef.of("初始密码", F_PASSWORD, "留空默认 Firestar@2026，首登强制修改", "Firestar@2026")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 用户名需全局唯一、手机号需唯一；"
                + "4. 组织名称/岗位编码/角色编码必须为系统已存在记录；5. 首登强制修改密码。";
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> usernames = new HashSet<>();
        Set<String> phones = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String username = val(row, F_USERNAME);
            if (username.isEmpty()) {
                err.append("用户名必填；");
            } else if (!USERNAME_PATTERN.matcher(username).matches()) {
                err.append("用户名格式错误（小写字母开头，仅小写字母/数字/下划线）；");
            } else if (!usernames.add(username)) {
                err.append("批内用户名重复；");
            } else if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
                err.append("用户名已存在；");
            }
            if (val(row, F_NICKNAME).isEmpty()) err.append("姓名必填；");
            String phone = val(row, F_PHONE);
            if (!phone.isEmpty()) {
                if (!phones.add(phone)) {
                    err.append("批内手机号重复；");
                } else if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone)) > 0) {
                    err.append("手机号已存在；");
                }
            }
            String status = val(row, F_STATUS);
            if (!status.isEmpty() && !"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                err.append("状态只能是 ENABLED/DISABLED；");
            }
            String org = val(row, F_ORG);
            if (!org.isEmpty() && orgMapper.selectCount(new LambdaQueryWrapper<Org>().eq(Org::getOrgName, org)) == 0) {
                err.append("组织【" + org + "】不存在；");
            }
            String post = val(row, F_POST);
            if (!post.isEmpty() && postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getPostCode, post)) == 0) {
                err.append("岗位编码【" + post + "】不存在；");
            }
            for (String code : splitCodes(val(row, F_ROLES))) {
                if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, code)) == 0) {
                    err.append("角色编码【" + code + "】不存在；");
                }
            }
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        int n = 0;
        for (Map<String, String> row : rows) {
            String username = val(row, F_USERNAME);
            String org = val(row, F_ORG);
            String post = val(row, F_POST);
            Long orgId = org.isEmpty() ? null : orgMapper.selectOne(
                    new LambdaQueryWrapper<Org>().eq(Org::getOrgName, org).last("LIMIT 1")).getId();
            Long postId = post.isEmpty() ? null : postMapper.selectOne(
                    new LambdaQueryWrapper<Post>().eq(Post::getPostCode, post).last("LIMIT 1")).getId();

            User user = new User();
            user.setUsername(username);
            user.setNickname(val(row, F_NICKNAME));
            String pwd = val(row, F_PASSWORD);
            user.setPassword(passwordEncoder.encode(pwd.isEmpty() ? DEFAULT_PASSWORD : pwd));
            user.setForceChangePassword(1);
            user.setDeptId(orgId);
            user.setPostId(postId);
            user.setPhone(val(row, F_PHONE));
            user.setEmail(val(row, F_EMAIL));
            String status = val(row, F_STATUS);
            user.setStatus(status.isEmpty() ? "ENABLED" : status);
            user.setCreatedBy(operatorId);
            user.setExt(importExt(batchNo));
            userMapper.insert(user);

            for (String code : splitCodes(val(row, F_ROLES))) {
                Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, code).last("LIMIT 1"));
                if (role == null) continue; // validate 已校验，防御性跳过
                UserRole ur = new UserRole();
                ur.setUserId(user.getId());
                ur.setRoleId(role.getId());
                ur.setGrantedBy(operatorId);
                ur.setGrantedAt(LocalDateTime.now());
                userRoleMapper.insert(ur);
            }
            n++;
        }
        return n;
    }

    /** 校验通过后可安全调用：从 ext 写期初标记（与方案 §5.2 一致）。 */
    static Map<String, Object> importExt(String batchNo) {
        return Map.of("imported", true, "source", "OPENING_IMPORT", "import_batch_no", batchNo);
    }

    static List<String> splitCodes(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split("[,，;；]")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
