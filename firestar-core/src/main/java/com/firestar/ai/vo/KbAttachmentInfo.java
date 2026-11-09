package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * REFERENCE 附件卡片 VO（前端补充材料卡片用）。
 *
 * <p>在 {@code sys_kb_attachment} 基础字段上顺带带回原文件名（来自 {@code sys_file_storage.original_name），
 * 便于前端展示与拼下载/预览链接；不含正文，按需单独拉取文件字节。</p>
 */
@Getter
@Setter
public class KbAttachmentInfo {

    private Long id;
    private Long docId;
    private Long fileId;
    /** 原文件名（展示 + 下载命名） */
    private String originalName;
    /** 1=纳入检索（下次发布切片）；0=仅挂接不检索 */
    private Integer indexable;
    private Integer egressAllowed;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
