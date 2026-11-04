package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 通知模板表（管理员可改文案，{{变量}} 占位）。
 * 由 V30__p2_notification_module.sql 创建；改表请走 Flyway V{n} 增量脚本，然后同步本文件。
 *
 * ⚠️ 无 version 字符串列：BaseEntity 已有乐观锁 version(INT)，
 *    再加一个字符串 version 会撞名（P1 协议版本表因此把版本号字段命名为别的，
 *    通知模板不需要"版本历史"，直接砍掉更干净）。
 *
 * 【渲染规则】title_tpl / content_tpl 中的 {{变量}} 由 NotificationService 替换；
 * 缺失变量留空并 warn，不抛异常（模板是运营资产，不该因一个变量拼错就断掉通知链路）。
 */
@Getter
@Setter
@TableName(value = "sys_notification_template", autoResultMap = true)
public class NotificationTemplate extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 模板编码（NotifyCommand.code 对应）：APPROVAL_TODO / SYSTEM_NOTICE ... */
    private String code;

    /** 模板名称（中文，管理端下拉/列表展示用，运营可改）。由 V31 增加。 */
    private String templateName;

    /** 通知类型（与 code 同值，保留独立列便于按类型查询） */
    private String type;

    /** 标题模板 */
    private String titleTpl;

    /** 正文模板 */
    private String contentTpl;

    /** 是否启用：0 = 停用（停用后该 code 的通知直接跳过，不落库不推送） */
    private Boolean enabled;
}
