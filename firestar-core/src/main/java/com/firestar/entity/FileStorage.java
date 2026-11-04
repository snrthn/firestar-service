package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_file_storage")
public class FileStorage extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 文件名（实际落盘名，服务端生成） */
    private String fileName;

    /** 原始文件名（仅用于展示与命名规范溯源，绝不拼进 objectKey —— 防路径穿越与编码坑） */
    private String originalName;

    /** 文件别名（V23；文件管理处维护：上传填写 / 列表编辑，仅展示与审计用，为空回退 originalName） */
    private String alias;

    /** 存储路径（磁盘绝对路径 或 OSS objectKey） */
    private String storagePath;

    /** 存储类型（字典 storage_type）：LOCAL 本地磁盘 / OSS 阿里云对象存储 */
    private String storageType;

    /** OSS Bucket 名（多环境不同桶：test/pre 共用 stage，prod 用 prod；LOCAL 时为空） */
    private String bucketName;

    /** 文件类型（字典 file_type） */
    private String fileType;

    /** 关联业务类型（泛化，如 invoice/receipt） */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 上传人 ID */
    private Long uploaderId;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件内容 SHA-256（完整性校验 / 去重） */
    private String fileHash;

    /** 状态（字典 file_status）：NORMAL 正常 / ARCHIVED 已归档 */
    private String status;

    /** 下载次数（冗余计数，避免列表页联表 count） */
    private Integer downloadCount;

    /** MIME 类型 */
    private String mimeType;
}
