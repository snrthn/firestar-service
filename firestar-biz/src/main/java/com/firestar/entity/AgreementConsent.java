package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 协议同意证据（只增不删）。撤回写 WITHDRAW 新行，不删旧 AGREE 行。
 * 由 V25__p1_param_setting_and_agreement.sql 创建；改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件。
 */
@Getter
@Setter
@TableName(value = "sys_agreement_consent")
public class AgreementConsent extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** → sys_user.id */
    private Long userId;

    /** → sys_agreement.id */
    private Long agreementId;

    /** → sys_agreement_version.id（存 id 不存字符串，三年后仍能原样复现当时同意的文本） */
    private Long versionId;

    /** AGREE/WITHDRAW */
    private String action;

    /** LOGIN/SETTINGS */
    private String channel;

    /** 客户端 IP（合规审计要） */
    private String ip;

    /** 客户端 UA（合规审计要） */
    private String userAgent;
}
