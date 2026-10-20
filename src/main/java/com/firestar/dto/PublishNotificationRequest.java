package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 管理端「发布通知」请求（P2 验收触发器）。
 *
 * 【与 NotifyCommand 的分工】管理端手动发布是运营动作，优先直接给成品文案；
 * 填了 code 则改走模板渲染（title/content 忽略），方便复用同一文案格式。
 *
 * 【dedupKey】手动发布默认不去重（每次提交都是一条新通知）——dedup_key 是
 * NOT NULL + UNIQUE，由 Service 生成 "MANUAL:{uuid}" 占位。
 * 确实想合并重复提交的场景（如前端防重发），由调用方显式传稳定值。
 */
@Getter
@Setter
public class PublishNotificationRequest {

    /** 可选：模板编码。填了则按模板渲染，忽略 title/content */
    private String code;

    /** 标题（不填 code 时必填） */
    private String title;

    /** 正文（不填 code 时必填） */
    private String content;

    /** 模板变量（填 code 时生效） */
    private java.util.Map<String, String> vars;

    /** 点击跳转相对路径（可选） */
    private String linkUrl;

    /** 级别：INFO / WARN / URGENT（可选，默认 INFO） */
    private String level;

    /** 收件人 userId 列表（必填，空则拒绝——防误广播） */
    private List<Long> receivers;
}
