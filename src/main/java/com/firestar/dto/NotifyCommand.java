package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 通知命令：业务侧调用 NotificationService#notify 的唯一入参。
 *
 * 业务代码不认识 SSE、不建表、不写推送——只填命令对象。
 *
 * 【receivers 语义】显式 userId 列表；空 = 不发（不提供"全员广播"快捷方式，
 * 广播必须由调用方显式给出全员 id 列表，防误广播）。
 *
 * 【dedupKey 语义】不传则按 {type}:{bizType}:{bizId}:{receiverId} 拼；
 * 传了则以调用方为准（如同一审批节点重复触发只提醒一次）。
 */
@Getter
@Setter
public class NotifyCommand {

    /** 模板编码（对应 sys_notification_template.code；模板不存在时该命令作废并 warn） */
    private String code;

    /** 收件人 userId 列表（空 = 不发） */
    private java.util.List<Long> receivers;

    /** 模板变量（{{key}} 替换；缺失留空并 warn，不抛） */
    private Map<String, String> vars;

    /** 关联业务类型（可选） */
    private String bizType;

    /** 关联业务 ID（可选，字符串以兼容非数字主键） */
    private String bizId;

    /** 点击跳转相对路径（可选） */
    private String linkUrl;

    /** 级别：INFO / WARN / URGENT（可选，默认 INFO） */
    private String level;

    /** 去重键（可选，默认按规则拼接） */
    private String dedupKey;

    /** 操作人 userId（写 created_by；系统触发可空） */
    private Long operatorId;
}
