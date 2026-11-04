package com.firestar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置绑定（application-{profile}.properties 前缀 firestar.storage.*）。
 *
 * <pre>
 *   firestar.storage.type = local | oss
 *   firestar.storage.local.dir              本地磁盘根目录（type=local 生效）
 *   firestar.storage.oss.endpoint           OSS 访问域名（跨地域只能用公网/自定义域名）
 *   firestar.storage.oss.bucket             Bucket 名（test/pre → fire-star-stage，prod → fire-star-prod）
 *   firestar.storage.oss.access-key-id      RAM 子账号 AK（严禁入库，走 .env / 云效 Secrets）
 *   firestar.storage.oss.access-key-secret  同上
 *   firestar.storage.oss.base-dir           环境前缀（dev/ test/ pre/ prod/）
 *   firestar.storage.oss.public-domain      已备案自定义域名（oss.snrthn.com），签发签名 URL 与在线预览用
 *   firestar.storage.oss.sign-url-expire-minutes  签名 URL 有效期（分钟，默认 10）
 * </pre>
 *
 * 【为什么下载必须走签名 URL 而不是服务端中转】
 * Bucket 在北京、ECS 在张家口 → 跨地域。阿里云规则：跨地域访问 OSS 需走公网或云企业网，
 * 内网 Endpoint 不可用。于是中转下载要付两次流出费：OSS 外网流出（→ECS）+ ECS 出方向（→用户）。
 * 改为后端校验通过后签发时效签名 URL、浏览器直连 OSS，只付一次，且不占 ECS 出网带宽。
 * 注意：上传流量在阿里云一律免费，所以「上传走服务端中转」没有额外成本。
 *
 * 【为什么 base-dir 之外还要分 Bucket】
 * 只分前缀是软隔离：一旦某环境误配 base-dir，或清理任务按前缀删，就会波及生产。
 * 不同 Bucket 是硬隔离，RAM 策略能在权限层兜底。
 */
@Component
@ConfigurationProperties(prefix = "firestar.storage")
public class StorageProperties {

    /** 存储类型：local（本地磁盘） / oss（阿里云对象存储） */
    private String type = "local";

    private final Local local = new Local();

    private final Oss oss = new Oss();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public Oss getOss() {
        return oss;
    }

    /** 本地磁盘存储配置 */
    public static class Local {

        /** 本地文件根目录，相对路径以进程工作目录为基准 */
        private String dir = "./uploads";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    /** 阿里云 OSS 配置 */
    public static class Oss {

        /** OSS 访问域名。跨地域场景下只能填公网域名或已备案自定义域名（内网 Endpoint 不可用） */
        private String endpoint;

        private String bucket;

        private String accessKeyId;

        private String accessKeySecret;

        /** 环境前缀，与 spring.profiles.active 对齐（dev/ test/ pre/ prod） */
        private String baseDir = "prod";

        /** 已备案自定义域名（如 oss.snrthn.com）。为空则回退用 endpoint 签发 URL */
        private String publicDomain;

        /** 签名 URL 有效期（分钟） */
        private int signUrlExpireMinutes = 10;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }

        public String getPublicDomain() {
            return publicDomain;
        }

        public void setPublicDomain(String publicDomain) {
            this.publicDomain = publicDomain;
        }

        public int getSignUrlExpireMinutes() {
            return signUrlExpireMinutes;
        }

        public void setSignUrlExpireMinutes(int signUrlExpireMinutes) {
            this.signUrlExpireMinutes = signUrlExpireMinutes;
        }
    }
}
