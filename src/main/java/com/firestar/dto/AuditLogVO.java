package com.firestar.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 操作审计视图对象。
 *
 * <p>beforeValue / afterValue 为「关键字段 diff」Map（仅声明过的字段，不做全量镜像），
 * 前端可直接渲染成「改前 → 改后」对比。operatorName 由后端按 operatorId 回填。
 */
@Data
public class AuditLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    /** 操作人 ID */
    private Long operatorId;
    /** 操作人账号（按 operatorId 回填，便于列表直接展示） */
    private String operatorName;
    /** 操作类型（字典 audit_operation）：CREATE / UPDATE / DELETE / UPLOAD ... */
    private String operationType;
    /** 目标对象类型：USER / ROLE / MENU / DICT_TYPE / DICT_DATA / FILE ... */
    private String targetType;
    /** 目标对象 ID */
    private Long targetId;
    /** 改前关键字段快照 */
    private Map<String, Object> beforeValue;
    /** 改后关键字段快照 */
    private Map<String, Object> afterValue;
    /** 操作时间 */
    private LocalDateTime operatedAt;
    /** 操作 IP */
    private String ipAddress;
    /** 备注 */
    private String remark;
}
