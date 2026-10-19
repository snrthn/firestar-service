package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 协议展示/弹层/个人中心通用的视图对象。
 * 一个 VO 覆盖三种场景：
 *   - active（登录后告知/展示）：填 code/name/version/changeSummary/content
 *   - pending（强制确认弹层）：额外依赖 needReconsent 区分首登/升级文案
 *   - 个人中心已同意列表：额外填 lastAction/consentedAt
 */
@Getter
@Setter
public class AgreementVO {

    /** → sys_agreement.id（前端回写 consent/withdraw 用） */
    private Long agreementId;

    /** TERMS/PRIVACY */
    private String code;

    /** 协议名称 */
    private String name;

    /** 生效版本号 v1/v2... */
    private String version;

    /** 生效版本 id（回写 consent/withdraw 用，存 id 不存字符串，合规可溯源） */
    private Long versionId;

    /** 变更摘要（弹层左侧） */
    private String changeSummary;

    /** Markdown 全文（弹层右侧 / 展示页） */
    private String content;

    /** 是否要求重同意（决定弹层文案：首次确认 / 协议已更新） */
    private Boolean needReconsent;

    /** 当前用户对生效版本的最后动作 AGREE/WITHDRAW/null */
    private String lastAction;

    /** 最后同意时间（个人中心展示） */
    private LocalDateTime consentedAt;
}
