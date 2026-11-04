package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_org")
public class Org extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 部门名称 */
    private String orgName;

    /** 上级部门 ID（自引用，0=根） */
    private Long parentId;

    /** 部门负责人 ID → sys_user.id */
    private Long leaderId;

    /** 排序 */
    private Integer sortNo;

    /** 状态（字典 common_status，三层停用代替删除） */
    private String status;
}
