package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /** 扩展名白名单（红线 4：上传要校验格式，防恶意文件） */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "doc", "docx", "xls", "xlsx", "txt", "csv", "zip", "ofd");

    /** 与 application.properties 的 spring.servlet.multipart.max-file-size 保持一致，此处为二次兜底 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;

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
    @Auditable(operationType = "UPLOAD", targetType = "FILE", targetIdSpEl = "#result.id", entityClass = FileStorage.class, fields = {"originalName", "fileType", "bizType", "fileSize", "storageType", "status"})
    public FileVO upload(MultipartFile file, String bizType, Long bizId, String fileType) {
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
            entity.setStoragePath(objectKey);
            entity.setStorageType(storage.type());
            entity.setBucketName(bucket);
            entity.setFileType(StringUtils.hasText(fileType) ? fileType : "ATTACHMENT");
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

    @Override
    public IPage<FileVO> list(Integer pageNum, Integer pageSize, String bizType, String fileType, String keyword) {
        LambdaQueryWrapper<FileStorage> wrapper = new LambdaQueryWrapper<>();
        // 已删除的不进列表（sys_ 表不带逻辑删除，靠 status 停用）
        wrapper.eq(FileStorage::getStatus, STATUS_NORMAL);
        wrapper.eq(StringUtils.hasText(bizType), FileStorage::getBizType, bizType);
        wrapper.eq(StringUtils.hasText(fileType), FileStorage::getFileType, fileType);
        wrapper.and(StringUtils.hasText(keyword), w -> w
                .like(FileStorage::getOriginalName, keyword)
                .or().like(FileStorage::getFileName, keyword));
        wrapper.orderByDesc(FileStorage::getCreatedAt);

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

        return url;
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
    public IPage<FileDownloadLogVO> downloadLogs(Long fileId, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<FileDownloadLog> wrapper = new LambdaQueryWrapper<FileDownloadLog>()
                .eq(fileId != null, FileDownloadLog::getFileId, fileId)
                .orderByDesc(FileDownloadLog::getDownloadedAt);
        IPage<FileDownloadLog> page = downloadLogMapper.selectPage(page(pageNum, pageSize), wrapper);
        Map<Long, String> names = uploaderNames(page.getRecords(), FileDownloadLog::getDownloaderId);
        return page.convert(log -> {
            FileDownloadLogVO vo = new FileDownloadLogVO();
            vo.setId(log.getId());
            vo.setFileId(log.getFileId());
            vo.setBizType(log.getBizType());
            vo.setBizId(log.getBizId());
            vo.setDownloaderId(log.getDownloaderId());
            vo.setDownloaderName(names.get(log.getDownloaderId()));
            vo.setDownloadedAt(log.getDownloadedAt());
            vo.setDownloadIp(log.getDownloadIp());
            return vo;
        });
    }

    @Override
    public void stream(Long id, boolean preview, HttpServletResponse response) {
        FileStorage file = load(id);
        response.setContentType(StringUtils.hasText(file.getMimeType())
                ? file.getMimeType() : "application/octet-stream");
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
        LocalDate today = LocalDate.now();
        return String.format("%s/%d/%02d/%02d/%s.%s",
                biz, today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID().toString().replace("-", ""), ext);
    }

    private String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1).toLowerCase();
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
