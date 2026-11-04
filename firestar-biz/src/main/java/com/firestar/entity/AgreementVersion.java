package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 协议版本（只增不改）。每次发版插入新行，永不 UPDATE content。
 * 由 V25__p1_param_setting_and_agreement.sql 创建；改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件。
 */
@Getter
@Setter
@TableName(value = "sys_agreement_version")
public class AgreementVersion extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** → sys_agreement.id */
    private Long agreementId;

    /** 版本号 v1/v2...（列名 ver，避开 BaseEntity 的乐观锁列 version） */
    private String ver;

    /** Markdown 快照（只增不改，合规可溯源） */
    private String content;

    /** 变更摘要（首读/升级弹层左侧展示） */
    private String changeSummary;

    /** 生效时间 */
    private LocalDateTime effectiveAt;

    /** 是否要求已同意用户重新同意（存量全量弹协议靠此标记，首次靠无记录） */
    private Boolean needReconsent;

    /** 发布人 → sys_user.id */
    private Long publishedBy;

    /** 状态（字典 common_status） */
    private String status;
}
