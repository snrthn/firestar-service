package com.firestar.service.impl;

import com.firestar.config.StorageProperties;
import com.firestar.exception.BizException;
import com.firestar.service.FileStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现（dev 环境默认）。
 *
 * <p>用途：本地开发不依赖云账号；同时也作为 OSS 不可用时的降级实现。
 * 注意本地存储是「单机」的 —— 多实例部署时各实例磁盘不共享，故 test/pre/prod 必须走 OSS。
 *
 * <p>下载不走签名 URL（本地没有签名能力），改为后端流式接口
 * {@code /files/{id}/raw}，由 FileController 读取磁盘文件输出。
 */
@Service
@ConditionalOnProperty(name = "firestar.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final StorageProperties properties;

    public LocalFileStorageServiceImpl(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String type() {
        return "LOCAL";
    }

    @Override
    public String store(InputStream in, String objectKey, String mimeType, long size) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("文件写入本地磁盘失败：" + e.getMessage());
        }
        return null;
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            // 删除失败不阻断主流程（文件已成为孤儿，靠定时清理兜底），仅记录
            throw new BizException("删除本地文件失败：" + e.getMessage());
        }
    }

    @Override
    public String accessUrl(Long fileId, String objectKey, String displayName, boolean preview) {
        String name = URLEncoder.encode(displayName == null ? "" : displayName, StandardCharsets.UTF_8);
        return "/files/" + fileId + "/raw?preview=" + (preview ? 1 : 0) + "&name=" + name;
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public InputStream read(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException e) {
            throw new BizException("读取本地文件失败：" + e.getMessage());
        }
    }

    /**
     * 解析 objectKey 到磁盘绝对路径，并做路径穿越防护。
     * objectKey 由服务端按 "{bizType}/{yyyy}/{MM}/{dd}/{uuid}.{ext}" 生成，理论上不含 "..",
     * 但 normalize + startsWith 是必要的纵深防御 —— 一旦将来允许外部传入 key，
     * 少了这步就能被 "../../etc/passwd" 打穿。
     */
    private Path resolve(String objectKey) {
        Path root = Paths.get(properties.getLocal().getDir()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法的文件存储路径：" + objectKey);
        }
        return target;
    }
}
