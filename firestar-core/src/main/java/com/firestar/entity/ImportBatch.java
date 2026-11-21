package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 历史数据导入批次 —— 一次导入的唯一审计摘要（方案 v3）。
 *
 * <p>【审计纪律】一次导入 = 本表一条记录，不建行级表、不写 sys_audit_log。
 * {@code ext} 存失败行错误数组（仅任务输出，非审计）+ 对账差异摘要 + 金额合计。
 * 不做逻辑删，永久留痕；管理员仅可标记 ARCHIVED。</p>
 *
 * <p>【同 key 串行】{@code activeFlag=1} 时生成列 active_key 取 import_key 占位，
 * 由 DB 唯一索引 uk_active 保证同一 import_key 同时只有一个处理中批次；
 * 终态 activeFlag=0 时 active_key=NULL，唯一索引自动释放。</p>
 */
@Getter
@Setter
@TableName(value = "sys_import_batch", autoResultMap = true)
public class ImportBatch extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 批次号 IMP-{yyyyMMdd}-{uuid8} */
    private String batchNo;

    /** Handler key（customer/goods/project_opening/...） */
    private String importKey;

    /** 提交人 ID（审计摘要核心） */
    private Long operatorId;

    /** 原始文件名 */
    private String fileName;

    /** 原始文件 SHA-256 */
    private String fileSha256;

    /** sys_file_storage.id 指针（biz_type=import） */
    private Long fileStorageId;

    /** 阶段 QUEUED/PARSING/VALIDATING/WRITING/RECONCILING/DONE */
    private String phase;

    /** 状态 QUEUED/PROCESSING/SUCCESS/PARTIAL/FAILED/ARCHIVED */
    private String status;

    /** 1=处理中 0=终态（配合生成列 active_key） */
    private Integer activeFlag;

    /** 解析总行数（上限 2000） */
    private Integer totalRows;

    /** 成功行数 */
    private Integer successRows;

    /** 失败行数 */
    private Integer failRows;

    /** 已处理行数 */
    private Integer processedRows;

    /** 进度 0-100 */
    private Integer progressPct;

    /** 修正批次指向的原批次号 */
    private String parentBatchNo;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 完成通知是否已发（防重） */
    private Integer notifySent;

    /** 失败行[{rowNo,rawData,errorMsg}] + reconcileSummary + amountTotal */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> ext;

    /** 备注 / 失败原因摘要 */
    private String remark;

    // ===== ext 内的结构化读取（不落库字段） =====

    @TableField(exist = false)
    private List<ErrorRow> errorRows;

    /** 错误行（ext.errors 的元素结构） */
    @Getter
    @Setter
    public static class ErrorRow {
        /** Excel 中的物理行号（含表头，从 2 开始） */
        private int rowNo;
        /** 该行原始列值（field → 原始字符串） */
        private Map<String, Object> rawData;
        /** 校验错误说明（多条件用分号拼接） */
        private String errorMsg;
    }
}
