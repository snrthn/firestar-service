package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Setter;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 由 V1__baseline.sql 自动生成，请勿手改字段（改表请走 Flyway V{n} 增量脚本，
 * 然后同步本文件）。手工新增字段时务必三处对齐：DDL / 本实体 / 建表方案.md。
 */
@Getter
@Setter
@TableName(value = "sys_role", autoResultMap = true)
public class Role extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 父角色 ID（自引用，继承≤3 层） */
    private Long parentId;

    /** 数据范围（字典 data_scope） */
    private String dataScope;

    /** 自定义部门 ID 集合（data_scope=CUSTOM 时生效） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> customDeptIds;

    /** 排序 */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
