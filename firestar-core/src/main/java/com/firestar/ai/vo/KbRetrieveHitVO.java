package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 检索命中明细 VO（调试/可观测用）：对齐 {@code KbChunkHit} 的全部可读字段，
 * 前端「检索测试」面板据此展示命中片段、相关度与出处。
 */
@Getter
@Setter
public class KbRetrieveHitVO {

    private Long docId;
    private int version;
    private String sourceType;
    /** 文档标题（H1） */
    private String title;
    /** 标题路径 / 来源章节 */
    private String headingPath;
    private String anchor;
    /** 切片正文（MD 文本，前端完整展示便于核对召回质量） */
    private String content;
    /** 出处串：kb:doc/... / kb:help/... / kb:att/... */
    private String sourceRef;
    /** 重排得分（越大越相关） */
    private double score;
    /** 来源附件 id（仅附件命中 > 0） */
    private Long fileId;
}
