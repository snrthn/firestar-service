package com.firestar.service;

import com.firestar.dto.NotificationVO;
import com.firestar.dto.NotifyCommand;
import com.firestar.dto.PublishNotificationRequest;
import com.firestar.dto.TemplateSaveRequest;
import com.firestar.entity.Config;
import com.firestar.entity.Notification;
import com.firestar.entity.NotificationTemplate;
import com.firestar.entity.User;
import com.firestar.mapper.ConfigMapper;
import com.firestar.mapper.NotificationMapper;
import com.firestar.mapper.NotificationTemplateMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.realtime.RealtimeEvent;
import com.firestar.realtime.RealtimeEventType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通知中枢：唯一入口 + 事实表兜底 + SSE 加速。
 *
 * 【落地链路（方案 3.2，每一步都可独立关闭）】
 *   1. 全局开关 notify_global_switch（fail-close：查不到/解析失败 = 关，不猜）
 *   2. 模板渲染（{{var}} 替换，缺失留空 + warn，不抛——模板是运营资产）
 *   3. 收件人为空 = 不发（无全员广播快捷方式，防误广播）
 *   4. dedup 落库（dedup_key 唯一键 + INSERT IGNORE；先查后插有竞态，不用）
 *   5. SSE 推送（RealtimeEvent(NOTIFY) → 网关按 username 路由）
 *   6. unreadCount 以事实表 COUNT 为准（SSE 丢帧/断网都不丢数，前端重连校正）
 *
 * 【为什么 dedupKey 统一拼 :{receiverId}】调用方显式传的 key 作为 base，
 * 仍按收件人拆分——否则同一 key 发给 N 人时，第二人起 INSERT IGNORE
 * 全部命中唯一键被吞掉，只有第一人收到。
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    /** 全局开关 key（P1 已种，不新增） */
    private static final String CONFIG_KEY_SWITCH = "notify_global_switch";

    private static final String CHANNEL_INAPP = "INAPP";

    /**
     * 跳转链接合法性：站内相对路径（/ 开头，不含空白）或 http(s):// 外链
     * （host 必须有点分节，如 example.com:8080）。空 = 不跳转，合法。
     * 管理端手填是脏数据入口，不校验的话落库后前端 router.push 会把用户
     * 带到 404/白屏（2026-10-20 虎哥要求正则校验）。
     */
    private static final java.util.regex.Pattern LINK_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(/\\S*|https?://[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?)$");

    private static void validateLinkUrl(String linkUrl) {
        if (linkUrl == null || linkUrl.isBlank()) {
            return;
        }
        if (!LINK_PATTERN.matcher(linkUrl.trim()).matches()) {
            throw new IllegalArgumentException(
                    "跳转链接不合法：需为站内路径（以 / 开头）或 http(s):// 外链地址");
        }
    }

    private static final int MAX_PAGE_SIZE = 50;

    /** 管理端 records 分页默认每页条数（与前端 el-pagination 默认值一致） */
    private static final int DEFAULT_PAGE_SIZE = 10;

    @Resource
    private NotificationMapper notificationMapper;
    @Resource
    private NotificationTemplateMapper notificationTemplateMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ConfigMapper configMapper;
    @Resource
    private ApplicationEventPublisher eventPublisher;

    // ==================== 业务侧唯一入口 ====================

    @Override
    public void notify(NotifyCommand cmd) {
        if (!globalSwitchOn()) {
            log.debug("[notify] 全局开关关闭，跳过: code={}", cmd.getCode());
            return;
        }
        NotificationTemplate tpl = loadEnabledTemplate(cmd.getCode());
        if (tpl == null) {
            log.warn("[notify] 模板不存在或已停用，跳过: code={}", cmd.getCode());
            return;
        }
        List<Long> receivers = distinct(cmd.getReceivers());
        if (receivers.isEmpty()) {
            log.debug("[notify] 收件人为空，跳过: code={}", cmd.getCode());
            return;
        }
        // 链接脏数据不阻断通知本身（静默跳过风格）：warn 后按无链接发
        if (cmd.getLinkUrl() != null && !cmd.getLinkUrl().isBlank()
                && !LINK_PATTERN.matcher(cmd.getLinkUrl().trim()).matches()) {
            log.warn("[notify] linkUrl 不合法，按无链接发送: {}", cmd.getLinkUrl());
            cmd.setLinkUrl(null);
        }
        String title = render(tpl.getTitleTpl(), cmd.getVars());
        String content = render(tpl.getContentTpl(), cmd.getVars());
        String level = cmd.getLevel() == null || cmd.getLevel().isBlank() ? "INFO" : cmd.getLevel();
        String base = cmd.getDedupKey() != null && !cmd.getDedupKey().isBlank()
                ? cmd.getDedupKey()
                : tpl.getType() + ":" + nullToEmpty(cmd.getBizType()) + ":" + nullToEmpty(cmd.getBizId());

        List<Notification> rows = new ArrayList<>(receivers.size());
        for (Long rid : receivers) {
            Notification n = new Notification();
            n.setReceiverId(rid);
            n.setType(tpl.getType());
            n.setTitle(title);
            n.setContent(content);
            n.setBizType(cmd.getBizType());
            n.setBizId(cmd.getBizId());
            n.setLinkUrl(cmd.getLinkUrl());
            n.setLevel(level);
            n.setDedupKey(base + ":" + rid);
            n.setCreatedBy(cmd.getOperatorId());
            rows.add(n);
        }
        persistAndPush(rows);
    }

    // ==================== 用户侧 ====================

    @Override
    public long unreadCount(Long userId) {
        Long n = notificationMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, userId)
                .isNull(Notification::getReadAt));
        return n == null ? 0 : n;
    }

    @Override
    public List<NotificationVO> listForUser(Long userId, Long lastId, int size) {
        int limit = clampSize(size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(Notification::getReceiverId, userId);
        if (lastId != null) {
            qw.lt(Notification::getId, lastId);
        }
        qw.orderByDesc(Notification::getId).last("LIMIT " + limit);
        List<Notification> list = notificationMapper.selectList(qw);
        List<NotificationVO> vos = new ArrayList<>(list.size());
        for (Notification n : list) {
            vos.add(toVO(n));
        }
        return vos;
    }

    @Override
    public void markRead(Long userId, Long notificationId) {
        Notification exist = notificationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getId, notificationId)
                        .eq(Notification::getReceiverId, userId)
                        .select(Notification::getId, Notification::getReadAt, Notification::getVersion));
        // 不存在（别人的通知/脏 id）或已读 → 幂等直接返回
        if (exist == null || exist.getReadAt() != null) {
            return;
        }
        Notification upd = new Notification();
        upd.setId(notificationId);
        upd.setReadAt(LocalDateTime.now());
        upd.setReadChannel(CHANNEL_INAPP);
        upd.setUpdatedBy(userId);
        // 显式带 version：updateById 时实体 version=null 会让乐观锁插件静默放行
        // （AuditMetaObjectHandler 实锤过的坑），虽然本场景无并发冲突，但不留隐患
        upd.setVersion(exist.getVersion());
        notificationMapper.updateById(upd);
    }

    @Override
    public int markAllRead(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return notificationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getReceiverId, userId)
                        .isNull(Notification::getReadAt)
                        // wrapper.update(null) 不触发 MetaObjectHandler，时间字段显式给
                        .set(Notification::getReadAt, now)
                        .set(Notification::getReadChannel, CHANNEL_INAPP)
                        .set(Notification::getUpdatedAt, now)
                        .set(Notification::getUpdatedBy, userId));
    }

    // ==================== 管理侧 ====================

    @Override
    public int publish(PublishNotificationRequest req, Long operatorId) {
        if (!globalSwitchOn()) {
            throw new IllegalStateException("全局通知开关已关闭，不能发布");
        }
        List<Long> receivers = distinct(req.getReceivers());
        if (receivers.isEmpty()) {
            throw new IllegalArgumentException("收件人不能为空");
        }
        validateLinkUrl(req.getLinkUrl());
        String title;
        String content;
        String type = "SYSTEM_NOTICE";
        String dedupBase;
        if (req.getCode() != null && !req.getCode().isBlank()) {
            // 走模板渲染（复用 notify 链路）
            NotificationTemplate tpl = loadEnabledTemplate(req.getCode());
            if (tpl == null) {
                throw new IllegalStateException("模板不存在或已停用: " + req.getCode());
            }
            title = render(tpl.getTitleTpl(), req.getVars());
            content = render(tpl.getContentTpl(), req.getVars());
            type = tpl.getType();
            dedupBase = "MANUAL:" + UUID.randomUUID();
        } else {
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                throw new IllegalArgumentException("标题不能为空");
            }
            title = req.getTitle();
            content = req.getContent() == null ? "" : req.getContent();
            dedupBase = "MANUAL:" + UUID.randomUUID();
        }
        String level = req.getLevel() == null || req.getLevel().isBlank() ? "INFO" : req.getLevel();

        List<Notification> rows = new ArrayList<>(receivers.size());
        for (Long rid : receivers) {
            Notification n = new Notification();
            n.setReceiverId(rid);
            n.setType(type);
            n.setTitle(title);
            n.setContent(content);
            n.setLinkUrl(req.getLinkUrl());
            n.setLevel(level);
            n.setDedupKey(dedupBase + ":" + rid);
            n.setCreatedBy(operatorId);
            rows.add(n);
        }
        return persistAndPush(rows);
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<NotificationVO> listRecords(
            Integer pageNum, Integer pageSize, String type, String receiver,
            LocalDateTime from, LocalDateTime to) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(type != null && !type.isBlank(), Notification::getType, type)
                .ge(from != null, Notification::getCreatedAt, from)
                .le(to != null, Notification::getCreatedAt, to)
                .orderByDesc(Notification::getId);
        // 收件人检索：纯数字按 ID 精确（兼容），其余按 用户名/昵称 模糊匹配
        if (receiver != null && !receiver.isBlank()) {
            String kw = receiver.trim();
            if (kw.matches("\\d+")) {
                qw.eq(Notification::getReceiverId, Long.parseLong(kw));
            } else {
                List<Long> ids = userMapper.selectList(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                                        .select(User::getId)
                                        .and(w -> w.like(User::getUsername, kw).or().like(User::getNickname, kw)))
                        .stream().map(User::getId).toList();
                if (ids.isEmpty()) {
                    // 无匹配用户：空页（不降级为全量，避免误导成"全部记录"）
                    return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size, 0);
                }
                qw.in(Notification::getReceiverId, ids);
            }
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Notification> page =
                notificationMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
        List<Notification> list = page.getRecords();
        // 批量补收件人显示名（昵称优先，附用户名）：管理页展开明细用
        Map<Long, String> nameOf = resolveReceiverNames(list);
        List<NotificationVO> vos = new ArrayList<>(list.size());
        for (Notification n : list) {
            NotificationVO vo = toVO(n);
            vo.setReceiverName(nameOf.get(n.getReceiverId()));
            vos.add(vo);
        }
        // VO 分页回填（同 UserServiceImpl.pageUsers 模式：新建 VO Page 搬 total + records）
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<NotificationVO> voPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
        voPage.setTotal(page.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /** receiverId → 显示名「昵称（用户名）」，昵称为空退化为用户名；一次查询批量解析 */
    private Map<Long, String> resolveReceiverNames(List<Notification> list) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (list.isEmpty()) {
            return map;
        }
        List<Long> ids = list.stream().map(Notification::getReceiverId).distinct().toList();
        List<User> users = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .select(User::getId, User::getUsername, User::getNickname)
                        .in(User::getId, ids));
        for (User u : users) {
            String nick = u.getNickname() == null || u.getNickname().isBlank()
                    ? u.getUsername()
                    : u.getNickname() + "（" + u.getUsername() + "）";
            map.put(u.getId(), nick);
        }
        return map;
    }

    @Override
    public List<NotificationTemplate> listTemplates() {
        return notificationTemplateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationTemplate>()
                        .orderByAsc(NotificationTemplate::getCode));
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<NotificationTemplate> pageTemplates(
            Integer pageNum, Integer pageSize, String code, String templateName) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationTemplate> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.like(code != null && !code.isBlank(), NotificationTemplate::getCode, code)
                .like(templateName != null && !templateName.isBlank(), NotificationTemplate::getTemplateName, templateName)
                .orderByAsc(NotificationTemplate::getCode);
        return notificationTemplateMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
    }

    @Override
    public void updateTemplate(Long id, TemplateSaveRequest req) {
        NotificationTemplate exist = notificationTemplateMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("模板不存在: " + id);
        }
        if (req.getTitleTpl() == null || req.getTitleTpl().isBlank()) {
            throw new IllegalArgumentException("标题模板不能为空");
        }
        if (req.getContentTpl() == null || req.getContentTpl().isBlank()) {
            throw new IllegalArgumentException("正文模板不能为空");
        }
        NotificationTemplate upd = new NotificationTemplate();
        upd.setId(id);
        // 名称给空则保留原值（不允许把名称清空，下拉会显示空白项）
        String name = req.getTemplateName() == null ? null : req.getTemplateName().trim();
        upd.setTemplateName((name == null || name.isEmpty()) ? exist.getTemplateName() : name);
        upd.setTitleTpl(req.getTitleTpl());
        upd.setContentTpl(req.getContentTpl());
        upd.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
        upd.setVersion(exist.getVersion()); // 同上：显式 version，不让乐观锁静默放行
        notificationTemplateMapper.updateById(upd);
    }

    // ==================== 内部 ====================

    /** 落库 + 推送。返回实际入库条数（dedup 去重后）。 */
    private int persistAndPush(List<Notification> rows) {
        int inserted = 0;
        List<Long> touched = new ArrayList<>(rows.size());
        for (Notification n : rows) {
            inserted += notificationMapper.insertIgnore(n);
            touched.add(n.getReceiverId());
        }
        // id → username（网关按 username 路由；脏 id 跳过并 warn）
        Map<Long, String> usernameOf = resolveUsernames(touched);
        for (Long rid : usernameOf.keySet()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("unreadCount", unreadCount(rid));
            payload.put("latest", latestOf(rid));
            eventPublisher.publishEvent(new RealtimeEvent(
                    this, RealtimeEventType.NOTIFY, usernameOf.get(rid), payload));
        }
        if (inserted > 0) {
            log.info("[notify] 落库 {} 条（推送 {} 人）", inserted, usernameOf.size());
        }
        return inserted;
    }

    private NotificationTemplate loadEnabledTemplate(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return notificationTemplateMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationTemplate>()
                        .eq(NotificationTemplate::getCode, code)
                        .eq(NotificationTemplate::getEnabled, true));
    }

    private Map<Long, String> resolveUsernames(List<Long> userIds) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        List<User> users = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .select(User::getId, User::getUsername)
                        .in(User::getId, userIds));
        for (User u : users) {
            map.put(u.getId(), u.getUsername());
        }
        if (map.size() < userIds.size()) {
            log.warn("[notify] 部分收件人 userId 查不到用户名，将收不到 SSE: {}", userIds);
        }
        return map;
    }

    /** 最新一条（推送 latest 摘要；面板点开再拉全量） */
    private Map<String, Object> latestOf(Long userId) {
        Notification latest = notificationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, userId)
                        .orderByDesc(Notification::getId)
                        .last("LIMIT 1")
                        .select(Notification::getId, Notification::getType,
                                Notification::getTitle, Notification::getContent,
                                Notification::getLinkUrl,
                                Notification::getCreatedAt));
        Map<String, Object> m = new LinkedHashMap<>();
        if (latest == null) {
            return m;
        }
        m.put("id", latest.getId());
        m.put("type", latest.getType());
        m.put("title", latest.getTitle());
        m.put("content", latest.getContent());
        // linkUrl 必须随推送下发：插顶项若缺 linkUrl，详情弹窗「查看详情」按钮
        // 永远不显示（前端 onPush 无法从别处拿到，面板重拉前点开就是残缺项）
        m.put("linkUrl", latest.getLinkUrl());
        m.put("ts", latest.getCreatedAt());
        return m;
    }

    /**
     * {{var}} 替换。缺失变量留空并 warn——模板是运营资产，
     * 不该因一个变量名拼错就断掉整条通知链路。
     */
    private String render(String tpl, Map<String, String> vars) {
        if (tpl == null) {
            return "";
        }
        String out = tpl;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
            }
        }
        if (out.contains("{{") && log.isWarnEnabled()) {
            log.warn("[notify] 模板存在未替换变量: {}", out);
        }
        return out;
    }

    /**
     * 全局开关。fail-close：key 缺失或值不可解析 = 关。
     * 通知是"推送给用户"的动作，配置故障时安静不发好过疯狂乱发。
     */
    private boolean globalSwitchOn() {
        Config c = configMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Config>()
                        .eq(Config::getConfigKey, CONFIG_KEY_SWITCH)
                        .select(Config::getConfigValue));
        if (c == null || c.getConfigValue() == null) {
            log.warn("[notify] 全局开关 {} 缺失，按关闭处理（fail-close）", CONFIG_KEY_SWITCH);
            return false;
        }
        String v = c.getConfigValue().trim();
        boolean on = "true".equalsIgnoreCase(v) || "1".equals(v)
                || "y".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
        if (!on) {
            log.debug("[notify] 全局开关值为 {}，按关闭处理", v);
        }
        return on;
    }

    private NotificationVO toVO(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setLevel(n.getLevel());
        vo.setLinkUrl(n.getLinkUrl());
        vo.setBizType(n.getBizType());
        vo.setBizId(n.getBizId());
        vo.setRead(n.getReadAt() != null);
        vo.setCreatedAt(n.getCreatedAt());
        return vo;
    }

    private List<Long> distinct(List<Long> ids) {
        List<Long> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (Long id : ids) {
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
