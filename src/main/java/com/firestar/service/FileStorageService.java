package com.firestar.service;

import java.io.InputStream;

/**
 * 文件存储抽象（技术栈文档原本就定下的契约：「FileStorage 接口抽象，切换不改业务代码」）。
 *
 * <p>业务层（FileService）只依赖本接口，不感知底层是本地磁盘还是阿里云 OSS。
 * 切换只改配置 {@code firestar.storage.type}，实现类由
 * {@code @ConditionalOnProperty} 决定装配哪一个。
 *
 * <p><b>objectKey 由业务层生成并传入</b>，本接口只负责「按 key 存取」：
 * <pre>{bizType}/{yyyy}/{MM}/{dd}/{uuid}.{ext}</pre>
 * key 里带 uuid 是硬要求 —— OSS 同名 PutObject 会静默覆盖，是经典事故源。
 *
 * <p><b>上传与下载为什么不对称：</b>
 * 上传走服务端中转（文件流经过应用服务器），因为阿里云上传流量一律免费，
 * 且「先落存储、再写 DB，写 DB 失败就删存储」能保证不产生孤儿对象；
 * 下载走 {@link #accessUrl} 签发的时效签名 URL，浏览器直连 OSS，
 * 因为跨地域中转下载会重复计算流出流量，还占 ECS 出网带宽（详见 StorageProperties 注释）。
 */
public interface FileStorageService {

    /** 存储类型标识，落库到 sys_file_storage.storage_type：LOCAL / OSS */
    String type();

    /**
     * 写入文件。
     *
     * @param in        文件流（调用方负责关闭）
     * @param objectKey 对象键（含 bizType/日期/uuid，不含 bucket）
     * @param mimeType  MIME 类型
     * @param size      文件大小（字节），OSS 侧用于优化上传
     * @return Bucket 名；本地磁盘存储返回 null
     */
    String store(InputStream in, String objectKey, String mimeType, long size);

    /** 删除文件。对象不存在时静默成功（幂等），便于「写 DB 失败回滚已传对象」场景调用。 */
    void delete(String objectKey);

    /**
     * 生成访问地址。
     *
     * @param fileId       文件 ID（本地存储用它拼后端流式下载路径；OSS 不使用）
     * @param objectKey    对象键
     * @param displayName  下载/预览时展示给用户的文件名
     * @param preview      true=在线预览（Content-Disposition: inline）；false=附件下载（attachment）
     * @return OSS 返回带时效的绝对签名 URL；本地返回后端相对路径
     */
    String accessUrl(Long fileId, String objectKey, String displayName, boolean preview);

    /** 对象是否存在。用于上传前的重复判断与删除前的防御检查。 */
    boolean exists(String objectKey);

    /**
     * 读取文件流。
     *
     * <p>仅本地存储模式需要（本地没有签名能力，下载只能由后端代理输出）。
     * OSS 模式下请走 {@link #accessUrl} 让浏览器直连 —— 这里读流再转发出去，
     * 等于把「跨地域双重流量费 + 占 ECS 出网带宽」的毛病又请回来了。
     *
     * @return 文件流，调用方负责关闭
     */
    InputStream read(String objectKey);
}
