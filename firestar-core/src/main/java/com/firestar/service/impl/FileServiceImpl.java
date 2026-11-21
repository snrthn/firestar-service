package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.ai.kb.KbFileTextExtractor;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.IpUtil;
import com.firestar.dto.FileDownloadLogVO;
import com.firestar.dto.FileVO;
import com.firestar.entity.FileDownloadLog;
import com.firestar.entity.FileStorage;
import com.firestar.entity.User;
import com.firestar.annotation.Auditable;
import com.firestar.exception.BizException;
import com.firestar.mapper.FileDownloadLogMapper;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FileService;
import com.firestar.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件业务实现。
 *
 * <p><b>上传为什么走服务端中转：</b>
 * 阿里云上传流量一律免费，中转不产生额外流量成本；更重要的是能保证
 * 「先落存储、再写 DB，写 DB 失败就删掉已传对象」——不会出现 OSS 里躺着
 * 文件而 DB 没有记录、谁也不知道的孤儿对象（前端直传方案必须额外处理回调丢失）。
 *
 * <p><b>下载为什么不走中转：</b>
 * Bucket 在北京、ECS 在张家口（跨地域），中转下载要计两次流出费还占 ECS 出网带宽，
 * 所以这里只签发时效签名 URL 让浏览器直连 OSS（详见 StorageProperties 注释）。
 */
@Service
public class FileServiceImpl implements FileService {

    /** 扩展名白名单（红线 4：上传要校验格式，防恶意文件）
     *  V24 新增 mp3/mp4（媒体文件，配套前端音视频预览，2026-10-16）。
     *  V218：并入知识库解析器支持格式（KbFileTextExtractor.SUPPORTED_EXTENSIONS）——
     *  此前知识库附件 accept 含 md，上传却撞这里的旧白名单直接 500，两处共用真源防再漂移。 */
    private static final List<String> ALLOWED_EXT = Stream.concat(
            Stream.of("pdf", "jpg", "jpeg", "png", "gif", "webp", "bmp",
                    "doc", "docx", "xls", "xlsx", "txt", "csv", "zip", "ofd",
                    "mp3", "mp4"),
            KbFileTextExtractor.SUPPORTED_EXTENSIONS.stream())
            .distinct()
            .toList();

    /** 供同包单测校验「知识库附件格式全部可上传」契约，不开放给业务代码绕过校验。 */
    static boolean isAllowedExtension(String ext) {
        return ALLOWED_EXT.contains(ext);
    }

    /**
     * V206：file_type 值域（纯媒体格式）。服务端白名单，绝不把前端自由字符串直接落库。
     * 业务凭证语义归 file_biz_type，本集合不再收 BANK_RECEIPT / INVOICE / TEMP。
     */
    private static final Set<String> FILE_TYPES =
            Set.of("IMAGE", "PDF", "AUDIO", "VIDEO", "ATTACHMENT");

    /** V206：扩展名 → 媒体类型。只在 classify=true 且用户未手选时走；未命中一律 ATTACHMENT。 */
    private static final Map<String, String> EXT_FILE_TYPE = Map.ofEntries(
            Map.entry("pdf", "PDF"),
            Map.entry("jpg", "IMAGE"),
            Map.entry("jpeg", "IMAGE"),
            Map.entry("png", "IMAGE"),
            Map.entry("gif", "IMAGE"),
            Map.entry("webp", "IMAGE"),
            Map.entry("bmp", "IMAGE"),
            Map.entry("mp3", "AUDIO"),
            Map.entry("mp4", "VIDEO"));

    /** 与 application.properties 的 spring.servlet.multipart.max-file-size 保持一致，此处为二次兜底 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    /**
     * 扩展名 → 对外 Content-Type 白名单（**只由服务端按扩展名决定，绝不回放客户端声明**）。
     *
     * <p>【为什么必须这样：同源存储型 XSS】
     * 原实现把 {@code file.getContentType()}（完全由客户端控制的 multipart 头）存进
     * sys_file_storage.mime_type，再在 {@code /files/{id}/raw} 里原样 setContentType 回放。
     * 攻击者上传一个内容为 {@code <script>...</script>} 的 {@code a.txt}，把 multipart 里的
     * Content-Type 写成 {@code text/html}，就能拿到一个**与管理系统同源**的 HTML 页面：
     * 诱导管理员打开即可读取 localStorage 里的 token（前端就是存这儿）、以管理员身份调接口。
     * 扩展名白名单挡不住这一手 —— .txt 是允许的，问题出在被信任的 MIME 头。
     *
     * <p>所以：类型只认扩展名，映射不到的一律 {@code application/octet-stream}
     * （浏览器只会下载、不会渲染），并附带 {@code X-Content-Type-Options: nosniff}
     * 禁止浏览器自作聪明嗅探。html / htm 虽然为知识库解析而允许上传，但刻意不映射
     * text/html —— raw 接口回放同源 HTML 会复活上面的 XSS 攻击面。
     */
    private static final Map<String, String> SAFE_CONTENT_TYPE = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("txt", "text/plain;charset=UTF-8"),
            Map.entry("md", "text/plain;charset=UTF-8"),
            Map.entry("markdown", "text/plain;charset=UTF-8"),
            Map.entry("log", "text/plain;charset=UTF-8"),
            Map.entry("text", "text/plain;charset=UTF-8"),
            Map.entry("json", "text/plain;charset=UTF-8"),
            Map.entry("csv", "text/csv;charset=UTF-8"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("zip", "application/zip"),
            Map.entry("ofd", "application/ofd"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"));

    /** bizType 会拼进对象键（"<bizType>/yyyy/MM/dd/uuid.ext"），只允许安全字符 */
    private static final java.util.regex.Pattern BIZ_TYPE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_DELETED = "DELETED";

    private final FileStorageMapper fileMapper;
    private final FileDownloadLogMapper downloadLogMapper;
    private final UserMapper userMapper;
    private final FileStorageService storage;

    public FileServiceImpl(FileStorageMapper fileMapper, FileDownloadLogMapper downloadLogMapper,
                           UserMapper userMapper, FileStorageService storage) {
        this.fileMapper = fileMapper;
        this.downloadLogMapper = downloadLogMapper;
        this.userMapper = userMapper;
        this.storage = storage;
    }

    @Override
    @Auditable(operationType = "UPLOAD", targetType = "FILE", targetIdSpEl = "#result.id", entityClass = FileStorage.class, fields = {"originalName", "alias", "fileType", "bizType", "fileSize", "storageType", "status"})
    public FileVO upload(MultipartFile file, String bizType, Long bizId, String fileType, String alias,
                         boolean classify) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String original = StringUtils.cleanPath(
                Objects.requireNonNullElse(file.getOriginalFilename(), "unnamed"));
        String ext = extension(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException("不支持的文件类型：" + ext + "（仅允许 " + String.join("/", ALLOWED_EXT) + "）");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("文件大小超过 20MB 上限");
        }

        Path temp = null;
        try {
            // 先落临时文件再读，而不是 getBytes() 全量进内存：
            // 20MB 上限下并发几个就会吃掉几百 MB 堆内存，磁盘换内存更稳。
            temp = Files.createTempFile("firestar-upload-", "." + ext);
            file.transferTo(temp);

            String hash = sha256(temp);
            String objectKey = buildObjectKey(bizType, ext);
            long size = Files.size(temp);

            String bucket;
            try (InputStream in = Files.newInputStream(temp)) {
                bucket = storage.store(in, objectKey, file.getContentType(), size);
            }

            FileStorage entity = new FileStorage();
            entity.setFileName(objectKey.substring(objectKey.lastIndexOf('/') + 1));
            entity.setOriginalName(original);
            entity.setAlias(normalizeAlias(alias));
            entity.setStoragePath(objectKey);
            entity.setStorageType(storage.type());
            entity.setBucketName(bucket);
            entity.setFileType(resolveFileType(fileType, ext, classify));
            entity.setBizType(StringUtils.hasText(bizType) ? bizType : null);
            entity.setBizId(bizId);
            entity.setUploaderId(currentUserId());
            entity.setFileSize(size);
            entity.setFileHash(hash);
            entity.setMimeType(file.getContentType());
            entity.setStatus(STATUS_NORMAL);
            entity.setDownloadCount(0);

            try {
                fileMapper.insert(entity);
            } catch (Exception e) {
                // 关键：写 DB 失败必须回滚已上传的对象，否则 OSS 里就多一个没人认领的孤儿
                storage.delete(objectKey);
                throw new BizException("文件记录写入失败，已回滚上传：" + e.getMessage());
            }
            return toVO(entity, null);
        } catch (IOException e) {
            throw new BizException("文件处理失败：" + e.getMessage());
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * V206 媒体类型解析（classify 契约，见 FileService#upload）。
     *
     * <p>【为什么 classify 必须显式】上传接口只有一个 /files/upload，服务端无法从请求本身
     * 判断调用方是「文件管理页」还是「某张单据的附件上传」。若靠"是否传了 fileType"
     * 来猜，就会退回隐式契约：业务组件哪天顺手带一个类型，分类口径立刻漂掉。
     * 现在前端只能声明"要不要分类"，分类结果由服务端定，前端绕不过去。
     *
     * <p>包级可见 + static：便于 FileTypeResolveTest 纯逻辑验证，不需要 Spring 上下文、
     * 不需要 Mock FileStorageMapper / OSS，也不碰数据库。
     */
    static String resolveFileType(String fileType, String ext, boolean classify) {
        if (!classify) {
            // 其他所有业务场景：一律兜底，连伪造的 fileType 也忽略
            return "ATTACHMENT";
        }
        if (StringUtils.hasText(fileType)) {
            String v = fileType.trim().toUpperCase(Locale.ROOT);
            if (!FILE_TYPES.contains(v)) {
                throw new BizException("文件类型不合法：" + fileType
                        + "（仅允许 IMAGE/PDF/AUDIO/VIDEO/ATTACHMENT）");
            }
            return v;
        }
        return inferByExt(ext);
    }

    /** 按扩展名推导媒体类型；未命中（office/zip/ofd/txt/csv 等）归 ATTACHMENT。 */
    static String inferByExt(String ext) {
        if (!StringUtils.hasText(ext)) {
            return "ATTACHMENT";
        }
        return EXT_FILE_TYPE.getOrDefault(ext.toLowerCase(Locale.ROOT), "ATTACHMENT");
    }

    @Override
    public IPage<FileVO> list(Integer pageNum, Integer pageSize, String bizType, Long bizId, String fileType, String keyword) {
        // 数据隔离分流（2026-10-24 虎哥原意，本次修正实现）：
        // - 业务上下文（带 bizType，如审批详情拉某单附件）：附件归属业务单据、随单据可见性走，
        //   不按上传人隔离，否则审批人看不到别人传的附件（银行回单/签收证据等）。
        // - 文件管理「我的文件」浏览（无 bizType）：仅看自己上传的（uploaderId = 自己）。
        // 类级 file:view 门禁已移除（业务页读附件不应被文件模块权限卡住），读接口对登录用户开放；
        // 写操作（上传/删除/改名）仍由方法级 file:manage 收口。
        Long me = DataScopeHelper.currentUserId(userMapper);
        if (DataScopeHelper.denyAll(me)) {
            return page(pageNum, pageSize);
        }
        LambdaQueryWrapper<FileStorage> wrapper = new LambdaQueryWrapper<>();
        // 已删除的不进列表（sys_ 表不带逻辑删除，靠 status 停用）
        wrapper.eq(FileStorage::getStatus, STATUS_NORMAL);
        wrapper.eq(StringUtils.hasText(bizType), FileStorage::getBizType, bizType);
        wrapper.eq(bizId != null, FileStorage::getBizId, bizId);
        wrapper.eq(StringUtils.hasText(fileType), FileStorage::getFileType, fileType);
        wrapper.and(StringUtils.hasText(keyword), w -> w
                .like(FileStorage::getOriginalName, keyword)
                .or().like(FileStorage::getFileName, keyword));
        wrapper.orderByDesc(FileStorage::getCreatedAt);

        boolean businessContext = StringUtils.hasText(bizType);
        if (!businessContext) {
            // 仅「我的文件」浏览按上传人隔离；业务上下文放行（随单据可见性）
            DataScopeHelper.own(wrapper, FileStorage::getUploaderId, me);
        }

        IPage<FileStorage> page = fileMapper.selectPage(page(pageNum, pageSize), wrapper);
        Map<Long, String> names = uploaderNames(page.getRecords());
        return page.convert(f -> toVO(f, names.get(f.getUploaderId())));
    }

    @Override
    public FileVO detail(Long id) {
        return toVO(load(id), null);
    }

    @Override
    public String accessUrl(Long id, boolean preview, HttpServletRequest request) {
        FileStorage file = load(id);
        String url = storage.accessUrl(file.getId(), file.getStoragePath(), file.getOriginalName(), preview);

        // ⚠️ 只有真实下载才计数 + 落下载记录：预览是在线查看、不是下载行为。
        // 此前这里无条件 +1 并写记录，预览全被计成下载，审计口径直接失真（2026-10-16 修）。
        if (!preview) {
            // 计数用 SQL 自增而不是「查出来 +1 再 update」，避免并发下互相覆盖
            fileMapper.update(null, new LambdaUpdateWrapper<FileStorage>()
                    .eq(FileStorage::getId, id)
                    .setSql("download_count = download_count + 1"));

            FileDownloadLog log = new FileDownloadLog();
            log.setFileId(id);
            log.setBizType(file.getBizType());
            log.setBizId(file.getBizId());
            log.setDownloaderId(currentUserId());
            log.setDownloadedAt(LocalDateTime.now());
            log.setDownloadIp(IpUtil.getClientIp(request));
            downloadLogMapper.insert(log);
        }

        return url;
    }

    @Override
    @Auditable(operationType = "UPDATE", targetType = "FILE", targetIdSpEl = "#id", entityClass = FileStorage.class, fields = {"alias"})
    public FileVO updateAlias(Long id, String alias) {
        load(id);
        String normalized = normalizeAlias(alias);
        // 单列更新（不整行回写）；MP 的 UpdateWrapper.set 对 null 是显式 SET alias=null，清空可用
        fileMapper.update(null, new LambdaUpdateWrapper<FileStorage>()
                .eq(FileStorage::getId, id)
                .set(FileStorage::getAlias, normalized));
        return toVO(load(id), null);
    }

    @Override
    @Auditable(operationType = "DELETE", targetType = "FILE", targetIdSpEl = "#id", entityClass = FileStorage.class, fields = {"originalName", "fileType", "bizType", "status"})
    public void delete(Long id) {
        load(id);
        // ⚠️ 不删 OSS 对象、不物理删 DB 行：财务凭证有合规保留期，
        // 删除只做标记，便于追溯「谁在什么时候删了什么」。物理清理交给归档任务。
        fileMapper.update(null, new LambdaUpdateWrapper<FileStorage>()
                .eq(FileStorage::getId, id)
                .set(FileStorage::getStatus, STATUS_DELETED));
    }

    @Override
    public IPage<FileDownloadLogVO> downloadLogs(Long fileId, Integer pageNum, Integer pageSize, String keyword) {
        // 方案A：下载记录表只存 fileId，文件名/别名按 fileId 批量回查 sys_file_storage
        // （软删行仍在 —— 文件删了也要能看出当年下载过什么，审计口径）。
        // 关键字搜索同理：先反查文件 id 再过滤记录。两段式参数化查询，不拼 SQL，无注入。
        List<Long> keywordFileIds = null;
        if (StringUtils.hasText(keyword)) {
            LambdaQueryWrapper<FileStorage> fw = new LambdaQueryWrapper<>();
            fw.select(FileStorage::getId);
            fw.and(w -> w.like(FileStorage::getOriginalName, keyword)
                    .or().like(FileStorage::getAlias, keyword));
            keywordFileIds = fileMapper.selectList(fw).stream()
                    .map(FileStorage::getId).filter(Objects::nonNull).toList();
            if (keywordFileIds.isEmpty()) {
                // 没有文件命中 → 直接空页（避免 in() 空集合）
                return page(pageNum, pageSize);
            }
        }
        // 行级数据隔离（2026-10-24 虎哥）：非 ROLE_ADMIN 只看自己的下载记录
        // （downloaderId = 自己）。口径 = 「我下载过什么」，与登录日志/操作日志一致。
        // ⚠️ 取舍：文件属主因此看不到「谁下载了我的文件」——那是另一种审计视角，
        //    当前菜单（下载记录）没有该诉求，先按统一口径收敛，需要时再开白名单。
        Long me = DataScopeHelper.currentUserId(userMapper);
        if (DataScopeHelper.denyAll(me)) {
            return page(pageNum, pageSize);
        }
        LambdaQueryWrapper<FileDownloadLog> wrapper = new LambdaQueryWrapper<FileDownloadLog>()
                .eq(fileId != null, FileDownloadLog::getFileId, fileId)
                .in(keywordFileIds != null, FileDownloadLog::getFileId, keywordFileIds)
                .orderByDesc(FileDownloadLog::getDownloadedAt);
        DataScopeHelper.own(wrapper, FileDownloadLog::getDownloaderId, me);
        IPage<FileDownloadLog> page = downloadLogMapper.selectPage(page(pageNum, pageSize), wrapper);
        Map<Long, FileStorage> files = fileMap(page.getRecords());
        Map<Long, String> names = uploaderNames(page.getRecords(), FileDownloadLog::getDownloaderId);
        return page.convert(log -> {
            FileDownloadLogVO vo = new FileDownloadLogVO();
            vo.setId(log.getId());
            vo.setFileId(log.getFileId());
            FileStorage f = files.get(log.getFileId());
            vo.setFileName(f != null ? f.getOriginalName() : null);
            vo.setAlias(f != null ? f.getAlias() : null);
            vo.setBizType(log.getBizType());
            vo.setBizId(log.getBizId());
            vo.setDownloaderId(log.getDownloaderId());
            vo.setDownloaderName(names.get(log.getDownloaderId()));
            vo.setDownloadedAt(log.getDownloadedAt());
            vo.setDownloadIp(log.getDownloadIp());
            return vo;
        });
    }

    /** 按 fileId 批量回查文件（文件名/别名展示用）；本表无逻辑删除，软删行一并返回 */
    private Map<Long, FileStorage> fileMap(List<FileDownloadLog> logs) {
        List<Long> ids = logs.stream().map(FileDownloadLog::getFileId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileStorage::getId, Function.identity(), (a, b) -> a));
    }

    @Override
    public void stream(Long id, boolean preview, HttpServletResponse response) {
        FileStorage file = load(id);
        // ⚠️ 不再回放客户端声明的 mimeType（同源存储型 XSS，详见 SAFE_CONTENT_TYPE 注释）。
        // 类型一律按**服务端生成的**对象键扩展名决定，未知扩展名 → 二进制流强制下载。
        response.setContentType(safeContentType(file.getStoragePath()));
        // 禁止浏览器嗅探：即便服务端标了 text/plain，也不许它「看内容像 HTML 就当 HTML 渲染」。
        response.setHeader("X-Content-Type-Options", "nosniff");
        String name = URLEncoder.encode(
                StringUtils.hasText(file.getOriginalName()) ? file.getOriginalName() : "file",
                StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition",
                (preview ? "inline" : "attachment") + "; filename*=UTF-8''" + name);
        try (InputStream in = storage.read(file.getStoragePath())) {
            in.transferTo(response.getOutputStream());
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
    }

    // ===== 内部工具 =====

    private FileStorage load(Long id) {
        FileStorage file = fileMapper.selectById(id);
        if (file == null || STATUS_DELETED.equals(file.getStatus())) {
            throw new BizException("文件不存在或已被删除");
        }
        return file;
    }

    /**
     * objectKey：{bizType}/{yyyy}/{MM}/{dd}/{uuid}.{ext}
     * uuid 是硬要求 —— OSS 同名 PutObject 会静默覆盖，是经典事故源。
     * 环境前缀（test/ pre/ prod/）由存储层拼，业务层不感知。
     */
    private String buildObjectKey(String bizType, String ext) {
        String biz = StringUtils.hasText(bizType) ? bizType.trim() : "common";
        // bizType 由前端传入，会作为对象键的第一层目录。LocalFileStorageServiceImpl.resolve()
        // 已有 normalize + startsWith 兜底（挡住 ../../ 穿出 uploads 目录），但那属于
        // 「到磁盘写入那一刻才炸」的纵深防御；在这里先按白名单拒掉，报错更早也更清楚。
        if (!BIZ_TYPE_PATTERN.matcher(biz).matches()) {
            throw new BizException("业务类型不合法（仅允许字母、数字、下划线、短横线，最长 32 字符）");
        }
        LocalDate today = LocalDate.now();
        return String.format("%s/%d/%02d/%02d/%s.%s",
                biz, today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID().toString().replace("-", ""), ext);
    }

    private String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1).toLowerCase();
    }

    /** 按对象键（服务端生成的 uuid.ext）扩展名给出安全 Content-Type；未知类型强制二进制下载 */
    private String safeContentType(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return "application/octet-stream";
        }
        String type = SAFE_CONTENT_TYPE.get(extension(objectKey));
        return type != null ? type : "application/octet-stream";
    }

    /**
     * 别名归一（V23）：去首尾空白；空串归一为 null（回退显示 originalName）；
     * 超长直接拒（列 VARCHAR(255)）。上传与编辑别名共用，保证两处口径一致。
     */
    private String normalizeAlias(String alias) {
        if (!StringUtils.hasText(alias)) {
            return null;
        }
        String trimmed = alias.trim();
        if (trimmed.length() > 255) {
            throw new BizException("文件别名过长（最多 255 字符）");
        }
        return trimmed;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，理论上不可达
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !StringUtils.hasText(auth.getName())) {
            return null;
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, auth.getName())
                .select(User::getId));
        return user == null ? null : user.getId();
    }

    /** 批量查上传人/下载人姓名，避免列表页 N+1 */
    private Map<Long, String> uploaderNames(List<FileStorage> files) {
        return uploaderNames(files, FileStorage::getUploaderId);
    }

    private <T> Map<Long, String> uploaderNames(List<T> rows, Function<T, Long> idGetter) {
        List<Long> ids = rows.stream().map(idGetter).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, ids)
                .select(User::getId, User::getNickname, User::getUsername));
        return users.stream().collect(Collectors.toMap(User::getId,
                u -> StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername(),
                (a, b) -> a));
    }

    /**
     * 分页参数兜底（非法值回退 1/10，size 上限 500，与项目其他 Service 一致）。
     * 必须泛型化：文件列表与下载记录列表都用它，写死 FileStorage 会让后者类型不匹配。
     */
    private <T> Page<T> page(Integer pageNum, Integer pageSize) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 500);
        return new Page<>(num, size);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响主流程，JVM 退出后由 OS 清理
        }
    }

    private FileVO toVO(FileStorage f, String uploaderName) {
        FileVO vo = new FileVO();
        vo.setId(f.getId());
        vo.setFileName(f.getFileName());
        vo.setOriginalName(f.getOriginalName());
        vo.setAlias(f.getAlias());
        vo.setFileType(f.getFileType());
        vo.setBizType(f.getBizType());
        vo.setBizId(f.getBizId());
        vo.setFileSize(f.getFileSize());
        vo.setMimeType(f.getMimeType());
        vo.setStorageType(f.getStorageType());
        vo.setStatus(f.getStatus());
        vo.setDownloadCount(f.getDownloadCount());
        vo.setUploaderId(f.getUploaderId());
        vo.setUploaderName(uploaderName);
        vo.setCreatedAt(f.getCreatedAt());
        return vo;
    }
}
