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
import com.firestar.entity.ProcessDef;
import com.firestar.entity.Role;
import com.firestar.entity.User;
import com.firestar.entity.UserRole;
import com.firestar.exception.BizException;
import com.firestar.mapper.ConfigMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ProcessDefMapper;
import com.firestar.mapper.RoleMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.mapper.UserRoleMapper;
import com.firestar.service.FileStorageService;
import com.firestar.service.InitResetService;
import com.firestar.service.LogService;
import com.firestar.workflow.WorkflowEngineService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    /** 单个验证码挑战允许的最大失败次数（超限即作废，防 6 位数字枚举） */
    private static final int MAX_VERIFY_ATTEMPTS = 5;

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

    /** JWT 密钥（用于环境预检的弱密钥/默认值提示） */
    @Value("${jwt.secret:}")
    private String jwtSecret;

    private final ObjectProvider<FileStorageService> storageProvider;
    private final ObjectProvider<ProcessDefMapper> processDefMapperProvider;
    private final ObjectProvider<WorkflowEngineService> engineProvider;
    private final Environment environment;

    /** 验证码挑战缓存：key = username:type → 验证码 + 过期时间 + 来源 IP + 失败次数 */
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public InitResetServiceImpl(ConfigMapper configMapper, UserMapper userMapper,
                               UserRoleMapper userRoleMapper, RoleMapper roleMapper,
                               FileStorageMapper fileStorageMapper, LogService logService,
                               JdbcTemplate jdbcTemplate, DataSource dataSource,
                               ObjectProvider<FileStorageService> storageProvider,
                               ObjectProvider<ProcessDefMapper> processDefMapperProvider,
                               ObjectProvider<WorkflowEngineService> engineProvider,
                               Environment environment) {
        this.configMapper = configMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.fileStorageMapper = fileStorageMapper;
        this.logService = logService;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.storageProvider = storageProvider;
        this.processDefMapperProvider = processDefMapperProvider;
        this.engineProvider = engineProvider;
        this.environment = environment;
    }

    private static class Challenge {
        final String code;
        final long expireAt;
        final String ip;
        int attempts;
        Challenge(String code, long expireAt, String ip) {
            this.code = code;
            this.expireAt = expireAt;
            this.ip = ip;
            this.attempts = 0;
        }
    }

    @Override
    public InitStatusVO getStatus() {
        InitStatusVO vo = new InitStatusVO();
        vo.setInitialized("true".equalsIgnoreCase(getConfig(KEY_INITIALIZED)));
        vo.setTrialMode("true".equalsIgnoreCase(getConfig(KEY_TRIAL_MODE)));
        vo.setCanReset(vo.isInitialized() && vo.isTrialMode());
        vo.setAdminExists(adminExists());
        return vo;
    }

    /**
     * 是否存在超级管理员。
     *
     * <p>不能只按 {@code username='admin'} 判断：安装向导允许自定义管理员账号名，
     * 用自定义名初始化后仍按 'admin' 查会得到 false，重置页状态卡会错误显示「超管不存在」
     * 并给出误导性的故障信号。改为「存在绑定 ROLE_ADMIN 角色的启用用户」判断，
     * 兼容默认 admin 与自定义账号两种初始化路径。
     */
    private boolean adminExists() {
        Role adminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, ADMIN_ROLE_CODE));
        if (adminRole == null) {
            return userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, "admin")) > 0;
        }
        Long bound = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, adminRole.getId()));
        return bound != null && bound > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InitStatusVO init(InitRequest req) {
        // 并发初始化护栏：对 sys_config 的 sys_initialized 行加行锁（SELECT ... FOR UPDATE），
        // 两个并发初始化请求只有一个能拿到锁，第二个读到已翻转的 true 即被 403 拦截，
        // 避免「双双通过 initialized 检查、重复执行 setupAdmin + 写标记」的竞态。
        // 事务边界 = 本方法（@Transactional），锁持有到方法提交才释放。
        String locked = getConfigForUpdate(KEY_INITIALIZED);
        // 初始化完成后安装向导关闭：匿名端点收口，回真 HTTP 403
        // （ResponseStatusException 走 GlobalExceptionHandler 的 ResponseEntity 通道带出状态码，
        //  而非 BizException 的 HTTP 200 + body.code 壳——后者会被监控/curl -f 误判成成功）
        if ("true".equalsIgnoreCase(locked)) {
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
    public InitStatusVO getResetStatus() {
        // 与匿名端点 status() 的区别：本方法是「已登录」后才可达的完整状态，不做信息裁剪。
        // 重置页（system/reset）必须走这个口，否则拿不到 trialMode/canReset，
        // 两个按钮会永久 disabled（此前 reset.vue 误用了匿名裁剪版端点）。
        InitStatusVO vo = getStatus();
        vo.setLastResetTime(getConfig(KEY_LAST_RESET_TIME));
        vo.setLastResetBy(getConfig(KEY_LAST_RESET_BY));
        return vo;
    }

    @Override
    public ResetResultVO reset(String type, ResetRequest req, HttpServletRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String clientIp = IpUtil.getClientIp(request);
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
            String code = generateChallenge(username, type, clientIp);
            ResetResultVO vo = new ResetResultVO();
            vo.setExecuted(false);
            vo.setVerifyCode(code);
            vo.setMessage("已生成验证码，请在确认框中输入以执行重置");
            vo.setResetType(type);
            return vo;
        }
        // 验证码校验（带防爆破）：挑战必须存在、未过期、来源 IP 一致，且错误次数不超上限。
        // 每次失败累加 attempts，达到 MAX_VERIFY_ATTEMPTS 立即作废挑战，必须重新获取——
        // 否则 6 位数字验证码在 5 分钟窗口内可被暴力枚举（100 万空间对有脚本的人来说不够）。
        Challenge ch = challenges.get(challengeKey(username, type));
        if (ch == null || ch.expireAt < System.currentTimeMillis()) {
            challenges.remove(challengeKey(username, type));
            throw new BizException("验证码无效或已过期，请重新获取");
        }
        if (ch.ip != null && clientIp != null && !ch.ip.equals(clientIp)) {
            // 挑战与来源 IP 绑定：即使验证码在网络上被看到，换一台机器也用不了
            challenges.remove(challengeKey(username, type));
            throw new BizException("验证码与当前设备不匹配，请重新获取");
        }
        if (!ch.code.equals(req.getVerifyCode().trim())) {
            ch.attempts++;
            if (ch.attempts >= MAX_VERIFY_ATTEMPTS) {
                challenges.remove(challengeKey(username, type));
                throw new BizException("验证码错误次数过多，已作废，请重新获取");
            }
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
            // 密码错误同样计入尝试次数，防止「拿到验证码后无限猜密码」
            ch.attempts++;
            if (ch.attempts >= MAX_VERIFY_ATTEMPTS) {
                challenges.remove(challengeKey(username, type));
            }
            throw new BizException("管理员密码校验失败");
        }
        // 验证码一次性：无论后续成功与否都先摘除，避免同码重放
        challenges.remove(challengeKey(username, type));
        List<String> cleared = "full".equals(type) ? fullReset() : lightReset();
        writeResetAudit(username, type, request, cleared);
        setConfig(KEY_LAST_RESET_TIME, new Timestamp(System.currentTimeMillis()).toString());
        setConfig(KEY_LAST_RESET_BY, username);
        ResetResultVO vo = new ResetResultVO();
        vo.setExecuted(true);
        vo.setMessage("系统重置完成");
        vo.setResetType(type);
        vo.setClearedTables(cleared);
        // 完整重置后系统回到未初始化：前端据此登出并跳安装向导
        vo.setRequireReinstall("full".equals(type));
        return vo;
    }

    /** 轻度重置：清理业务数据表 + Flowable 运行时/历史 + 上传文件，保留系统配置 */
    private List<String> lightReset() {
        List<String> tables = new ArrayList<>();
        tables.addAll(listTables("fin_%"));
        // AI 对话侧业务数据（会话/消息/工具调用/消息反馈）一并清理——
        // 这些是用户/AI 在试运行期间产生的数据，属「业务数据」而非系统配置；
        // 知识库（sys_kb_doc/chunk/attachment）作为可复用的知识资产保留，不在此列。
        tables.addAll(listTables("ai_%"));
        tables.addAll(listTables("ACT_RU_%"));
        tables.addAll(listTables("ACT_HI_%"));
        // Flowable 7 把部分批处理/运行时表迁到了 FLW_ 前缀（FLW_RU_BATCH 等），
        // 只清 ACT_RU/HI 会漏掉运行期批处理数据。FLW_EVENT_* 属事件注册中心，
        // 项目已 flowable.eventregistry.enabled=false，表为空，一并清亦无副作用。
        tables.addAll(listTables("FLW_RU_%"));
        deleteFromTables(tables);
        deleteFiles();
        return tables;
    }

    /** 完整重置：Flyway 清库重建 + 重新播种（恢复出厂，超管一并重建） */
    private List<String> fullReset() {
        // 1. clean 之前先收集 OSS 对象键：flyway.clean() 会 drop 掉 sys_file_storage，
        //    之后再查就查不到记录，OSS 对象会变成永远清不掉的孤儿。这里先落内存清单。
        List<FileStorage> ossFiles = fileStorageMapper.selectList(
                new LambdaQueryWrapper<FileStorage>()
                        .eq(FileStorage::getStorageType, "OSS")
                        .ne(FileStorage::getBizType, "import"));
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        // 物理文件随 clean 已清表，这里清磁盘残留
        deletePhysicalFilesOnly();
        // 2. 清理 OSS 对象（用 clean 前收集的清单，避免孤儿）
        deleteOssObjects(ossFiles);
        // 3. 重新部署流程定义：flyway.clean() 会 drop 掉 ACT_RE_*（含 Flowable 部署），
        //    migrate 只会重建空表不会重放 BPMN（BPMN 是运行时部署动作，非 SQL 种子）。
        //    必须在这里主动重新部署，否则完整重置后审批流全部「流程未部署」。
        //    详见 ProcessDefAutoDeployRunner —— 它只在应用启动时跑一次。
        int deployed = redeployProcessDefs();
        // V2 种子已将 sys_initialized=false / trial_mode=true 重新注入，
        // 保留「未初始化」状态，使安装向导重新开放（对齐「完整重置后重新执行初始化向导」）
        List<String> cleared = new ArrayList<>();
        cleared.add("ALL_TABLES(flyway clean+migrate 重建并重新播种)");
        if (deployed > 0) {
            cleared.add("重新部署流程定义 " + deployed + " 条");
        }
        return cleared;
    }

    private void deleteOssObjects(List<FileStorage> ossFiles) {
        if (ossFiles == null || ossFiles.isEmpty()) return;
        FileStorageService storage = storageProvider.getIfAvailable();
        if (storage == null || !"OSS".equals(storage.type())) {
            // 当前装配的不是 OSS 实现（LOCAL 模式），历史 OSS 记录无法用当前客户端删除，
            // 记录一条日志交人工处理，不阻断重置主流程。
            return;
        }
        for (FileStorage f : ossFiles) {
            if (f.getStoragePath() == null || f.getStoragePath().isBlank()) continue;
            try {
                storage.delete(f.getStoragePath());
            } catch (Exception ignored) {
                // 单个对象删除失败不影响整体重置（对象已无 DB 引用，属可容忍残留）
            }
        }
    }

    /**
     * 完整重置后重新部署流程定义。
     *
     * <p>背景：{@code flyway.clean()} 会 drop 掉 ACT_RE_*（Flowable 部署记录），
     * {@code migrate()} 只重建空表，BPMN 不会被自动重放——因为部署是**运行时动作**，
     * 种子 SQL 里只有 {@code sys_process_def} 的配置（节点 JSON），没有 BPMN 文件。
     * {@link com.firestar.config.ProcessDefAutoDeployRunner} 只在应用启动时跑一次，
     * 重置期间不会触发。若不在此处补部署，完整重置后所有审批流都会报「流程未部署」。
     *
     * @return 成功部署的流程定义条数
     */
    private int redeployProcessDefs() {
        ProcessDefMapper defMapper = processDefMapperProvider.getIfAvailable();
        WorkflowEngineService engine = engineProvider.getIfAvailable();
        if (defMapper == null || engine == null) {
            return 0;
        }
        int ok = 0;
        try {
            List<ProcessDef> defs = defMapper.selectList(new LambdaQueryWrapper<ProcessDef>()
                    .eq(ProcessDef::getStatus, "ENABLED"));
            for (ProcessDef def : defs) {
                if (def.getNodeList() == null || def.getNodeList().isEmpty()) continue;
                try {
                    var model = engine.generateBpmn(
                            def.getProcessCode(), def.getProcessName(), def.getNodeList());
                    var result = engine.deploy(def.getProcessCode(), def.getProcessName(), model);
                    def.setFlowableDeploymentId(result.getDeploymentId());
                    def.setDefVersion(result.getVersion());
                    defMapper.updateById(def);
                    ok++;
                } catch (Exception ignored) {
                    // 单条失败不中断整体重置（与 ProcessDefAutoDeployRunner 同策略）
                }
            }
        } catch (Exception ignored) {
            // 整体失败同样不阻断重置：重置本身已成功，流程部署可由重启/手动部署补偿
        }
        return ok;
    }

    /**
     * 轻度重置的文件清理：LOCAL 删磁盘 + 删记录；OSS 交给当前装配的存储实现删对象 + 删记录。
     *
     * <p>V218：导入原始文件是审计物证（biz_type='import'），两种重置都必须保留——
     * sys_import_batch 不在 fin_% 清理范围，若这里删了文件就成「批次在、文件没」的断链，
     * 错误行回执也无法从原始文件还原。故按 biz_type 排除 import。
     *
     * <p>此前只处理 {@code storageType='LOCAL'}，走 OSS 的环境重置后对象成孤儿（DB 记录已删、
     * 桶里还在），且持续计费。这里按存储类型分流：LOCAL 走 File、其它交给 FileStorageService。
     */
    private void deleteFiles() {
        List<FileStorage> files = fileStorageMapper.selectList(
                new LambdaQueryWrapper<FileStorage>()
                        .ne(FileStorage::getBizType, "import"));
        FileStorageService storage = storageProvider.getIfAvailable();
        List<FileStorage> ossFiles = new ArrayList<>();
        for (FileStorage f : files) {
            if (f.getStoragePath() == null || f.getStoragePath().isBlank()) continue;
            if ("LOCAL".equalsIgnoreCase(f.getStorageType())) {
                try {
                    new File(f.getStoragePath()).delete();
                } catch (Exception ignored) {
                    // 个别文件删除失败不影响整体重置
                }
            } else {
                ossFiles.add(f);
            }
        }
        if (storage != null && "OSS".equals(storage.type())) {
            deleteOssObjects(ossFiles);
        }
        // 记录清理：非 import 的全部删掉（含 OSS 记录；即便当前客户端不是 OSS，
        // DB 记录也应随之清理，避免「记录在、对象不可达」的僵尸行）
        fileStorageMapper.delete(
                new LambdaQueryWrapper<FileStorage>()
                        .ne(FileStorage::getBizType, "import"));
    }

    private void deletePhysicalFilesOnly() {
        File dir = new File(uploadDir);
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            // V218：保留 import/ 目录（导入原始文件，审计物证，full reset 也不删）
            if (c.isDirectory() && "import".equals(c.getName())) continue;
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
        // 1. 数据库连通 + 版本
        boolean dbOk = false;
        String dbDetail = "";
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = one != null && one == 1;
            dbDetail = dbOk ? "SELECT 1 = 1" : "返回异常值";
            if (dbOk) {
                String ver = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
                dbDetail = "连接正常，MySQL " + ver;
                // 项目基线 MySQL 8（V1 baseline 用了 utf8mb4 + 8.0 默认排序规则），
                // 5.7 及以下不保证迁移脚本全部可执行，预检阶段就暴露，别等 migrate 才炸
                if (ver != null && (ver.startsWith("5.") || ver.startsWith("4."))) {
                    dbOk = false;
                    dbDetail = "版本过低：" + ver + "（要求 MySQL 8.x）";
                }
            }
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
        // 5. JWT 密钥强度（仅 prod 强校验）：dev/pre/test 用的是配置里写死的本地默认密钥，
        //    若一并判失败会把开发和演示环境的初始化直接锁死。生产环境则必须替换为随机长密钥，
        //    否则任何拿到源码的人都能自签 token 冒充管理员。
        String secret = jwtSecret == null ? "" : jwtSecret.trim();
        boolean weak = secret.length() < 32
                || secret.contains("DEV_ONLY")
                || secret.contains("CHANGE_ME")
                || secret.equals("${JWT_SECRET}");
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        boolean secretOk = !prod || !weak;
        String secretDetail;
        if (secret.isEmpty()) {
            secretDetail = "jwt.secret 未配置";
        } else if (!weak) {
            secretDetail = "长度 " + secret.length() + " 位";
        } else if (prod) {
            secretDetail = "生产环境使用默认/过短密钥，请通过环境变量 JWT_SECRET 注入 ≥32 位随机串";
        } else {
            secretDetail = "非生产环境，使用内置开发密钥（正式部署请替换）";
        }
        checks.add(new EnvCheckItem("JWT 密钥强度", secretOk, secretDetail));
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

    private String generateChallenge(String username, String type, String ip) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        challenges.put(challengeKey(username, type),
                new Challenge(code, System.currentTimeMillis() + CHALLENGE_TTL_MS, ip));
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

    /** 行级锁读 sys_config 值（须在事务内调用，配合 init() 的 @Transactional 使用） */
    private String getConfigForUpdate(String key) {
        Config c = configMapper.selectOne(
                new LambdaQueryWrapper<Config>().eq(Config::getConfigKey, key).last("FOR UPDATE"));
        return c == null ? null : c.getConfigValue();
    }

    private void setConfig(String key, String value) {
        Config c = new Config();
        c.setConfigValue(value);
        int updated = configMapper.update(c,
                new LambdaUpdateWrapper<Config>().eq(Config::getConfigKey, key));
        // 兜底 upsert：老库/异常库缺该 key 时，update 命中 0 行会静默「什么都没写」，
        // 导致 sys_initialized 永远翻转不了、初始化向导死循环。这里补一条 insert。
        // 仅当目标键确实不存在才插入，避免与 update 竞态重复。
        if (updated == 0 && getConfig(key) == null) {
            Config ins = new Config();
            ins.setConfigKey(key);
            ins.setConfigValue(value);
            ins.setConfigType("SYSTEM");
            ins.setRemark("初始化与重置自动写入");
            configMapper.insert(ins);
        }
    }
}
