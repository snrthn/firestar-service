package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 协议主体（一类型一条）。
 * 由 V25__p1_param_setting_and_agreement.sql 创建；改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_agreement")
public class Agreement extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 协议编码 TERMS/PRIVACY */
    private String code;

    /** 协议名称 */
    private String name;

    /** 作用域 PLATFORM/TENANT */
    private String scope;

    /** 状态（字典 common_status） */
    private String status;

    /** 当前生效版本 → sys_agreement_version.id（NULL=取最新已发布版本） */
    private Long currentVersionId;
}
