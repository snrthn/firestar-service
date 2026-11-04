package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件视图对象。
 *
 * <p>⚠️ 刻意不返回 storage_path / bucket_name 等存储内部信息：
 * 这些是后端拼接签名 URL 的依据，暴露给前端既无用途，又会在换成另一种存储时
 * 造成前端隐式依赖（前端一旦拿 path 自己拼地址，OSS 签名就绕过了权限校验）。
 * 前端要访问文件，一律走后端签发的 URL。
 */
@Getter
@Setter
public class FileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 落盘名（服务端生成） */
    private String fileName;

    /** 原始文件名（展示用） */
    private String originalName;

    /** 文件别名（文件管理处维护，展示/审计用；为空回退 originalName） */
    private String alias;

    /** 文件类型（字典 file_type） */
    private String fileType;

    /** 关联业务类型（泛化，如 invoice/receipt） */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 文件大小（字节） */
    private Long fileSize;

    private String mimeType;

    /** 存储类型：LOCAL / OSS */
    private String storageType;

    /** 状态：NORMAL / ARCHIVED / DELETED */
    private String status;

    /** 下载次数 */
    private Integer downloadCount;

    /** 上传人 ID */
    private Long uploaderId;

    /** 上传人姓名（联表带出，便于列表直接展示） */
    private String uploaderName;

    private LocalDateTime createdAt;
}
