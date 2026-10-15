package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件下载记录视图对象（对应红线 5「谁下载了什么文件、什么时间」）。
 *
 * <p>注：业务模型要求下载记录保留 90 天后清理 —— 它属于可清理的临时数据，
 * 与 sys_audit_log（永久保留、不可删改）定位不同，不要混为一谈。
 */
@Getter
@Setter
public class FileDownloadLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long fileId;

    /** 文件名（冗余，便于列表直接展示；文件被删后仍能看出下载过什么） */
    private String fileName;

    private String bizType;

    private Long bizId;

    private Long downloaderId;

    private String downloaderName;

    private LocalDateTime downloadedAt;

    private String downloadIp;
}
