package com.firestar.service.impl;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.firestar.config.StorageProperties;
import com.firestar.exception.BizException;
import com.firestar.service.FileStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 阿里云 OSS 存储实现（test / pre / prod）。
 *
 * <p><b>为什么 OSSClient 必须单例并在销毁时 shutdown：</b>
 * SDK 内部持有 HTTP 连接池。若每次上传都 new 一个 client 而不关闭，跑一段时间后会
 * 出现接口越来越慢直至 "Connection pool shut down" —— 这是 OSS 集成里典型的隐性故障。
 *
 * <p><b>为什么下载返回签名 URL 而不是流式转发：</b>
 * Bucket 在北京、ECS 在张家口（跨地域），内网 Endpoint 不可用。若由服务端中转下载，
 * 流量要计两次（OSS 外网流出 → ECS，再 ECS 出方向 → 用户），且占用 ECS 出网带宽。
 * 改为校验通过后签发短时效签名 URL、浏览器直连 OSS，只计一次且不经过 ECS。
 * AK/SK 始终留在服务端，签名 URL 到期即失效。
 *
 * <p><b>关于自定义域名（oss.snrthn.com）：</b>
 * 经实测（2026-10-16）确认两件事：
 * <ol>
 *   <li>通过<b>默认域名</b>访问时，OSS 会强制加 {@code Content-Disposition: attachment}，
 *       浏览器只能下载、无法在线预览；改绑自定义域名后该头消失，预览可用。
 *       （官方文档亦确认此规则，且仅限中国内地 region 的外网访问）</li>
 *   <li>自定义域名<b>必须先做「证书托管」才能走 HTTPS</b>。未配证书时服务端返回区域默认
 *       证书，SAN 不含自定义域名，客户端严格校验必然失败。故 public-domain 的协议
 *       继承 endpoint，不写死 https —— 见 {@link #accessEndpoint}。</li>
 * </ol>
 * 一个自定义域名只能绑一个 Bucket。当前 {@code oss.snrthn.com} 绑的是
 * <b>fire-star-prod</b>，因此只有 prod 能享受预览；test / pre 共用 fire-star-stage，
 * 无自定义域名，图片只能下载（PDF 预览不受此限制）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "firestar.storage.type", havingValue = "oss")
public class AliyunOssStorageServiceImpl implements FileStorageService {

    private final StorageProperties properties;

    private OSS ossClient;

    public AliyunOssStorageServiceImpl(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        StorageProperties.Oss cfg = properties.getOss();
        require(cfg.getEndpoint(), "firestar.storage.oss.endpoint");
        require(cfg.getAccessKeyId(), "firestar.storage.oss.access-key-id");
        require(cfg.getAccessKeySecret(), "firestar.storage.oss.access-key-secret");
        require(cfg.getBucket(), "firestar.storage.oss.bucket");

        // 访问域名：配了自定义域名就优先用它（预览可用），否则回退标准 endpoint
        String accessEndpoint = accessEndpoint(cfg);

        // ⚠️ CNAME 支持必须按「endpoint 是不是阿里云标准域名」决定：
        //    supportCname=false 时 SDK 会把 bucket 拼进 host（bucket.endpoint），
        //    若 endpoint 实际是自定义域名，拼完就变成 fire-star-stage.oss.snrthn.com，
        //    服务端按绑定桶算签名 → SignatureDoesNotMatch 403（实测复现）。
        ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
        conf.setSupportCname(!isStandardEndpoint(accessEndpoint));

        // ⚠️ trim 防御（2026-10-16 生产 InvalidAccessKeyId 排查）：AK/SK 若来源被
        // 尾随空格或 CR 污染（.env 被 Windows 编辑器动过、Secret 粘贴带空格），
        // SDK 会把脏值原样发给阿里云 →「AKID 不存在」。trim 掉这一类低级污染。
        String accessKeyId = cfg.getAccessKeyId().trim();
        String accessKeySecret = cfg.getAccessKeySecret().trim();

        this.ossClient = new OSSClientBuilder().build(
                accessEndpoint, accessKeyId, accessKeySecret, conf);
    }

    /**
     * 实际用于收发请求的域名：public-domain 优先，否则用 endpoint。
     *
     * <p>【为什么不做「签完名再替换 host」】
     * OSS 的 V1 签名把 Host 也算进 StringToSign，签名 URL 的 host 一旦被替换，
     * 服务端重算出的签名必然不一致 → 403。所以想要自定义域名的 URL，
     * 只能<b>从一开始就用自定义域名当 endpoint 去签名</b>，不能事后替换。
     *
     * <p>【协议（http / https）怎么定】
     * 不假设 https，而是<b>继承 endpoint 的协议</b>。原因：自定义域名只有在
     * OSS 控制台做过「证书托管」之后才支持 HTTPS，否则服务端返回的是
     * 区域默认证书（CN=cn-beijing.oss.aliyuncs.com，SAN 不含自定义域名），
     * 客户端严格校验会直接 CERTIFICATE_VERIFY_FAILED。若这里写死 https，
     * 配了域名却没配证书时会在首次上传时抛 SSL 异常（build() 阶段不发请求，
     * 所以启动不报错），排查时容易被误判成 AK/SK 或权限问题。
     * 需要 HTTPS 时显式写全 {@code https://oss.snrthn.com} 即可。
     */
    private String accessEndpoint(StorageProperties.Oss cfg) {
        String pub = cfg.getPublicDomain();
        if (pub == null || pub.isBlank()) {
            return cfg.getEndpoint();
        }
        if (pub.contains("://")) {
            return pub;
        }
        String scheme = "https";
        try {
            String s = URI.create(cfg.getEndpoint()).getScheme();
            if (s != null && !s.isBlank()) {
                scheme = s;
            }
        } catch (Exception ignored) {
            // endpoint 没写协议时按 https 处理，与 OSS 默认一致
        }
        return scheme + "://" + pub;
    }

    /** 阿里云标准 endpoint 形如 oss-cn-beijing.aliyuncs.com / *.aliyuncs.com */
    private boolean isStandardEndpoint(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            return host != null && host.endsWith(".aliyuncs.com");
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    @Override
    public String type() {
        return "OSS";
    }

    @Override
    public String store(InputStream in, String objectKey, String mimeType, long size) {
        String bucket = properties.getOss().getBucket();
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(size);
            if (mimeType != null && !mimeType.isBlank()) {
                meta.setContentType(mimeType);
            }
            ossClient.putObject(bucket, withBaseDir(objectKey), in, meta);
            return bucket;
        } catch (Exception e) {
            // ⚠️ 必须打完整堆栈 + 带 cause 抛：OSS SDK 的 "Failed to reset the request
            // input stream" 是二手错误（第一次请求在连接层失败、重试时 reset 流失败，
            // 原始网络错误被吞）。只取 getMessage() 会把 SSL/timeout/连接重置的真相
            // 全丢掉，线上日志只剩一行看不懂的话——2026-10-16 生产排查的教训。
            log.error("上传 OSS 失败，bucket={}, objectKey={}", bucket, withBaseDir(objectKey), e);
            throw new BizException("上传文件到 OSS 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            // 幂等：对象不存在时 OSS 仍返回 204，不会报错，便于「写 DB 失败回滚」调用
            ossClient.deleteObject(properties.getOss().getBucket(), withBaseDir(objectKey));
        } catch (Exception e) {
            log.error("删除 OSS 文件失败，objectKey={}", withBaseDir(objectKey), e);
            throw new BizException("删除 OSS 文件失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String accessUrl(Long fileId, String objectKey, String displayName, boolean preview) {
        StorageProperties.Oss cfg = properties.getOss();
        Date expiration = new Date(System.currentTimeMillis()
                + cfg.getSignUrlExpireMinutes() * 60L * 1000L);

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(cfg.getBucket(), withBaseDir(objectKey), HttpMethod.GET);
        request.setExpiration(expiration);

        ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
        overrides.setContentType(null);
        overrides.setContentDisposition(contentDisposition(preview, displayName));
        request.setResponseHeaders(overrides);

        URL url = ossClient.generatePresignedUrl(request);
        // 不能替换 host —— Host 参与签名，替换即 403。域名在 init() 时就已确定。
        return url.toString();
    }

    @Override
    public boolean exists(String objectKey) {
        return ossClient.doesObjectExist(properties.getOss().getBucket(), withBaseDir(objectKey));
    }

    @Override
    public InputStream read(String objectKey) {
        // 仅本地存储模式会用到；OSS 模式请走签名 URL，不要让文件流经应用服务器
        return ossClient.getObject(properties.getOss().getBucket(), withBaseDir(objectKey))
                .getObjectContent();
    }

    /**
     * 拼上环境前缀（dev/ test/ pre/ prod）。
     *
     * <p>放在存储层而不是业务层，是为了让「键怎么组织」这件事只有一个地方知道 ——
     * store / delete / accessUrl / exists 全部走这里，保证签名 URL 与实际对象的 key
     * 永远一致（若两处分别拼，一处漏了就会签出一个 404 的 URL，且很难在测试中发现）。
     *
     * <p>注意这是「软隔离」：真正的硬隔离靠 Bucket 分环境（fire-star-stage / fire-star-prod），
     * 前缀只是让同一个桶内的文件可区分、便于生命周期规则与人工盘点。
     */
    private String withBaseDir(String objectKey) {
        String base = properties.getOss().getBaseDir();
        if (base == null || base.isBlank()) {
            return objectKey;
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized + "/" + objectKey;
    }

    /**
     * Content-Disposition：inline 触发浏览器在线预览，attachment 触发下载。
     * 中文文件名必须按 RFC 5987 给 filename*（filename 只放 ASCII 兜底），
     * 否则下载下来是乱码、或 header 直接因非 ASCII 字符报错。
     */
    private String contentDisposition(boolean preview, String displayName) {
        String name = (displayName == null || displayName.isBlank()) ? "download" : displayName;
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return (preview ? "inline" : "attachment")
                + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new BizException("缺少必填配置：" + key + "（请检查 .env/<profile>.env 是否已注入）");
        }
    }
}
