package com.firestar.service;

import com.firestar.dto.AgreementVO;
import com.firestar.entity.AgreementVersion;

import java.util.List;

/**
 * 协议服务：公开告知 / 强制确认弹层 / 同意与撤回证据 / 管理员发版。
 *
 * 【合规要点】
 *   - consent 与 withdraw 都是「只增」：撤回写 WITHDRAW 新行，绝不物理删除 AGREE 行，
 *     保证三年后仍能证明「用户当时同意的是哪段文字」。
 *   - 存 version_id 而非 version 字符串：文案快照由 sys_agreement_version 承载，可溯源。
 */
public interface AgreementService {

    /** 登录后告知：返回每个 ENABLED 协议的当前生效版本（含全文） */
    List<AgreementVO> listActive();

    /** 强制确认弹层：返回当前用户尚未对生效版本做出 AGREE 的协议 */
    List<AgreementVO> listPending(Long userId);

    /** 个人中心已同意列表：每个协议当前用户对生效版本的最后动作与时间 */
    List<AgreementVO> listConsents(Long userId);

    /** 同意（只增，action=AGREE） */
    void consent(Long userId, Long agreementId, Long versionId, String channel, String ip, String userAgent);

    /** 撤回（只增，action=WITHDRAW，不删旧记录） */
    void withdraw(Long userId, Long agreementId, Long versionId, String ip, String userAgent);

    /** 管理员发布新版本，返回新版本 id */
    Long publishVersion(Long agreementId, String content, String changeSummary, Boolean needReconsent, Long publishedBy);

    /** 协议版本历史（管理页） */
    List<AgreementVersion> listVersions(Long agreementId);
}
