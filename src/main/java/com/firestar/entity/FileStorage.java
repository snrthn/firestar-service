package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_file_storage")
public class FileStorage extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 文件名 */
    private String fileName;

    /** 存储路径（磁盘或 OSS Key） */
    private String storagePath;

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

    /** MIME 类型 */
    private String mimeType;
}
