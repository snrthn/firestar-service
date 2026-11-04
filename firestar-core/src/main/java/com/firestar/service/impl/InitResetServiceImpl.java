package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.common.IpUtil;
import com.firestar.dto.InitRequest;
import com.firestar.dto.InitStatusVO;
import com.firestar.dto.ResetRequest;
import com.firestar.dto.EnvCheckItem;
import com.firestar.dto.ResetResultVO;
import com.firestar.entity.AuditLog;
import com.firestar.entity.Config;
import com.firestar.entity.FileStorage;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.exception.BizException;
import com.firestar.mapper.ConfigMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.InitResetService;
import com.firestar.service.LogService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.io.File;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统初始化与重置实现。
 *
 * <p>关键约定（与现有基建对齐）：
 * <ul>
 *   <li>配置标记走 {@code sys_config} 表的 5 个种子键（V2 已注入）：
 *       sys_initialized / trial_mode / force_change_password / last_reset_time / last_reset_by；</li>
 *   <li>RBAC/字典/菜单等种子数据由 Flyway 在启动时完成，init 不再重复播种；</li>
 *   <li>full 重置采用 Flyway {@code clean()+migrate()} 恢复出厂（DDL + 种子全回灌，超管随种子重建，
 *       随后必须重新执行初始化向导并设置管理员密码），故无需在 Java 层搬运种子数据；</li>
 *   <li>审计复用 {@link LogService#saveAudit(AuditLog)}（REQUIRES_NEW，与业务解耦）。</li>
 * </ul>
 */
@Service
public class InitResetServiceImpl implements InitResetService {

    private static final String KEY_INITIALIZED = "sys_initialized";
    private static final String KEY_TRIAL_MODE = "trial_mode";
    private static final String KEY_FORCE_PWD = "force_change_password";
    private static final String KEY_LAST_RESET_TIME = "last_reset_time";
    private static final String KEY_LAST_RESET_BY = "last_reset_by";
    private static final String ADMIN_ROLE_CODE = "ROLE_ADMIN";
    private static final String CONFIRM_WORD = "RESET";
    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000L;

    private final ConfigMapper configMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final FileStorageMapper fileStorageMapper;
    private final LogService logService;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${firestar.storage.local.dir:./uploads}")
    private String uploadDir;

    /** 验证码挑战缓存：key = username:type → 验证码 + 过期时间 */
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public InitResetServiceImpl(ConfigMapper configMapper, UserMapper userMapper,
                               UserRoleMapper userRoleMapper, RoleMapper roleMapper,
                               FileStorageMapper fileStorageMapper, LogService logService,
                               JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.configMapper = configMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.logService = logService;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    private static class Challenge {
        final String code;
        final long expireAt;
        Challenge(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

    @Override
    public InitStatusVO getStatus() {
        InitStatusVO vo = new InitStatusVO();
        vo.setInitialized("true".equalsIgnoreCase(getConfig(KEY_INITIALIZED)));
        vo.setTrialMode("true".equalsIgnoreCase(getConfig(KEY_TRIAL_MODE)));
        vo.setCanReset(vo.isInitialized() && vo.isTrialMode());
        vo.setAdminExists(userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin")) > 0);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InitStatusVO init(InitRequest req) {
        // 初始化完成后安装向导关闭：匿名端点收口，回真 HTTP 403
        // （ResponseStatusException 走 GlobalExceptionHandler 的 ResponseEntity 通道带出状态码，
        //  而非 BizException 的 HTTP 200 + body.code 壳——后者会被监控/curl -f 误判成成功）
        if ("true".equalsIgnoreCase(getConfig(KEY_INITIALIZED))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "系统已完成初始化，安装向导已关闭");
        }
        if (req.getAdminPassword() == null || req.getAdminPassword().isBlank()) {
            throw new BizException("请设置管理员密码");
        }
        String username = (req.getAdminUsername() == null || req.getAdminUsername().isBlank())
                ? "admin" : req.getAdminUsername().trim();
        String password = req.getAdminPassword();
        String nickname = (req.getAdminNickname() == null || req.getAdminNickname().isBlank())
                ? "系统管理员" : req.getAdminNickname().trim();
        if (password.length() < 6) {
            throw new BizException("管理员密码长度至少 6 位");
        }
        // 步骤 1：环境检查（库连通 / Flowable 表 / 上传目录可写 / 磁盘空间）
        for (EnvCheckItem c : doEnvCheck()) {
            if (!c.isOk()) {
                throw new BizException("环境预检未通过：" + c.getName() + " - " + c.getDetail());
            }
        }
        // 步骤 4：初始化管理员账号（超管已随 Flyable V4 种子存在时则更新密码 + 绑定角色）
        setupAdmin(username, password, nickname);
        // 步骤 6：写入完成标记
        setConfig(KEY_INITIALIZED, "true");
        setConfig(KEY_TRIAL_MODE, "true");
        setConfig(KEY_FORCE_PWD, "true");
        return getStatus();
    }

    private void setupAdmin(String username, String password, String nickname) {
        Role adminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, ADMIN_ROLE_CODE));
        if (adminRole == null) {
            throw new BizException("ROLE_ADMIN 角色不存在，请先完成基础种子数据");
        }
        User exist = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exist == null) {
            User u = new User();
            u.setUsername(username);
            u.setNickname(nickname);
            u.setPassword(passwordEncoder.encode(password));
            u.setForceChangePassword(1);
            u.setStatus("ENABLED");
            userMapper.insert(u);
            bindRole(u.getId(), adminRole.getId());
        } else {
            User u = new User();
            u.setId(exist.getId());
            u.setPassword(passwordEncoder.encode(password));
            u.setForceChangePassword(1);
            u.setStatus("ENABLED");
            userMapper.updateById(u);
            bindRole(exist.getId(), adminRole.getId());
        }
    }

    private void bindRole(Long userId, Long roleId) {
        Long cnt = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId).eq(UserRole::getRoleId, roleId));
        if (cnt == null || cnt == 0) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    @Override
    public ResetResultVO reset(String type, ResetRequest req, HttpServletRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!"light".equals(type) && !"full".equals(type)) {
            throw new BizException("重置类型只能是 light 或 full");
        }
        // 重置安全红线：仅试运行模式可用（真 HTTP 403，非 200 壳）
        if (!"true".equalsIgnoreCase(getConfig(KEY_TRIAL_MODE))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "试运行模式已关闭，重置功能不可用");
        }
        if (!CONFIRM_WORD.equals(req.getConfirmText())) {
            throw new BizException("请输入确认词 " + CONFIRM_WORD + " 以确认重置");
        }
        // 验证码：首次（无码）生成并返回；二次（带码）校验后执行
        if (req.getVerifyCode() == null || req.getVerifyCode().isBlank()) {
            String code = generateChallenge(username, type);
            ResetResultVO vo = new ResetResultVO();
            vo.setExecuted(false);
            vo.setVerifyCode(code);
            vo.setMessage("已生成验证码，请在确认框中输入以执行重置");
            vo.setResetType(type);
            return vo;
        }
        Challenge ch = challenges.get(challengeKey(username, type));
        if (ch == null || ch.expireAt < System.currentTimeMillis()
                || !ch.code.equals(req.getVerifyCode().trim())) {
            throw new BizException("验证码无效或已过期，请重新获取");
        }
        // 二次确认（P2 加固）：必须提供当前管理员登录密码，防止仅凭管理员会话即可清库。
        // 仅在执行重置（携带 verifyCode）时校验。
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new BizException("请输入当前管理员登录密码以确认重置");
        }
        User me = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getPassword));
        if (me == null || !passwordEncoder.matches(req.getPassword(), me.getPassword())) {
            throw new BizException("管理员密码校验失败");
        }
        List<String> cleared = "full".equals(type) ? fullReset() : lightReset();
        challenges.remove(challengeKey(username, type));
        writeResetAudit(username, type, request, cleared);
        setConfig(KEY_LAST_RESET_TIME, new Timestamp(System.currentTimeMillis()).toString());
        setConfig(KEY_LAST_RESET_BY, username);
        ResetResultVO vo = new ResetResultVO();
        vo.setExecuted(true);
        vo.setMessage("系统重置完成");
        vo.setResetType(type);
        vo.setClearedTables(cleared);
        return vo;
    }

    /** 轻度重置：清理业务数据表 + Flowable 运行时/历史 + 上传文件，保留系统配置 */
    private List<String> lightReset() {
        List<String> tables = new ArrayList<>();
        tables.addAll(listTables("fin_%"));
        tables.addAll(listTables("ACT_RU_%"));
        tables.addAll(listTables("ACT_HI_%"));
        deleteFromTables(tables);
        deleteFiles();
        return tables;
    }

    /** 完整重置：Flyway 清库重建 + 重新播种（恢复出厂，超管一并重建） */
    private List<String> fullReset() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        // 物理文件随 clean 已清表，这里清磁盘残留
        deletePhysicalFilesOnly();
        // V2 种子已将 sys_initialized=false / trial_mode=true 重新注入，
        // 保留「未初始化」状态，使安装向导重新开放（对齐「完整重置后重新执行初始化向导」）
        List<String> cleared = new ArrayList<>();
        cleared.add("ALL_TABLES(flyway clean+migrate 重建并重新播种)");
        return cleared;
    }

    private void deleteFiles() {
        List<FileStorage> files = fileStorageMapper.selectList(
                new LambdaQueryWrapper<FileStorage>().eq(FileStorage::getStorageType, "LOCAL"));
        for (FileStorage f : files) {
            if (f.getStoragePath() != null && !f.getStoragePath().isBlank()) {
                try {
                    new File(f.getStoragePath()).delete();
                } catch (Exception ignored) {
                    // 个别文件删除失败不影响整体重置
                }
            }
        }
        fileStorageMapper.delete(
                new LambdaQueryWrapper<FileStorage>().eq(FileStorage::getStorageType, "LOCAL"));
    }

    private void deletePhysicalFilesOnly() {
        File dir = new File(uploadDir);
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            try {
                deleteRecursively(c);
            } catch (Exception ignored) {
                // 忽略单个文件删除异常
            }
        }
    }

    private void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    private void deleteFromTables(List<String> tables) {
        if (tables.isEmpty()) return;
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            for (String t : tables) {
                jdbcTemplate.execute("DELETE FROM `" + t + "`");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private List<String> listTables(String likePattern) {
        List<String> result = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?", likePattern);
        for (Map<String, Object> r : rows) {
            result.add(String.valueOf(r.get("TABLE_NAME")));
        }
        return result;
    }

    /** 环境预检：返回各项明细（不抛异常，供 /install 页展示；init() 内据此判断中断） */
    private List<EnvCheckItem> doEnvCheck() {
        List<EnvCheckItem> checks = new ArrayList<>();
        // 1. 数据库连通
        boolean dbOk = false;
        String dbDetail = "";
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = one != null && one == 1;
            dbDetail = dbOk ? "SELECT 1 = 1" : "返回异常值";
        } catch (Exception e) {
            dbDetail = "查询失败：" + e.getMessage();
        }
        checks.add(new EnvCheckItem("数据库连通性", dbOk, dbDetail));
        // 2. Flowable 表就绪
        boolean flowOk = false;
        String flowDetail = "";
        try {
            List<Map<String, Object>> flow = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ACT_RU_TASK'");
            flowOk = !flow.isEmpty();
            flowDetail = flowOk ? "ACT_RU_TASK 存在" : "ACT_RU_TASK 缺失";
        } catch (Exception e) {
            flowDetail = "查询失败：" + e.getMessage();
        }
        checks.add(new EnvCheckItem("流程引擎表 (Flowable)", flowOk, flowDetail));
        // 3. 上传目录可写
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();
        boolean dirOk = dir.canWrite();
        checks.add(new EnvCheckItem("文件存储目录可写", dirOk, dirOk ? uploadDir : "不可写：" + uploadDir));
        // 4. 磁盘空间
        long free = new File(".").getFreeSpace();
        boolean spaceOk = free >= 100L * 1024 * 1024;
        checks.add(new EnvCheckItem("磁盘可用空间 (≥100MB)", spaceOk,
                String.format("可用 %.1f MB", free / 1024.0 / 1024.0)));
        return checks;
    }

    @Override
    public List<EnvCheckItem> precheck() {
        // 初始化完成后安装向导关闭：预检明细（环境信息）不再对匿名暴露，回真 HTTP 403
        // （ResponseStatusException → GlobalExceptionHandler 用 ResponseEntity 带出状态码；
        //  此前 BizException(403) 会被 handleBiz 写成 HTTP 200 + body.code，监控侧误判成功）
        if ("true".equalsIgnoreCase(getConfig(KEY_INITIALIZED))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "系统已完成初始化，安装向导已关闭");
        }
        return doEnvCheck();
    }

    private String generateChallenge(String username, String type) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        challenges.put(challengeKey(username, type),
                new Challenge(code, System.currentTimeMillis() + CHALLENGE_TTL_MS));
        return code;
    }

    private String challengeKey(String username, String type) {
        return username + ":" + type;
    }

    private void writeResetAudit(String username, String type, HttpServletRequest request,
                                List<String> cleared) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        AuditLog log = new AuditLog();
        log.setOperatorId(u == null ? null : u.getId());
        log.setOperationType("RESET");
        log.setTargetType("SYSTEM");
        log.setOperatedAt(LocalDateTime.now());
        log.setIpAddress(IpUtil.getClientIp(request));
        log.setRemark("系统" + ("full".equals(type) ? "完整" : "轻度") + "重置");
        Map<String, Object> after = new HashMap<>();
        after.put("resetType", type);
        after.put("clearedTables", cleared);
        log.setAfterValue(after);
        logService.saveAudit(log);
    }

    // ===== sys_config 读写（按 config_key） =====
    private String getConfig(String key) {
        Config c = configMapper.selectOne(
                new LambdaQueryWrapper<Config>().eq(Config::getConfigKey, key));
        return c == null ? null : c.getConfigValue();
    }

    private void setConfig(String key, String value) {
        Config c = new Config();
        c.setConfigValue(value);
        configMapper.update(c, new LambdaUpdateWrapper<Config>().eq(Config::getConfigKey, key));
    }
}
