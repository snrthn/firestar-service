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
@TableName(value = "sys_file_download_log")
public class FileDownloadLog extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 文件 ID → sys_file_storage.id */
    private Long fileId;

    /** 关联业务类型 */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 下载人 ID */
    private Long downloaderId;

    /** 下载时间 */
    private LocalDateTime downloadedAt;

    /** 下载 IP */
    private String downloadIp;

    /** 下载次数（文档卡片漏项，方案补） */
    private Integer downloadCount;
}
